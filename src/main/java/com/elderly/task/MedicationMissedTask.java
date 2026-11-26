package com.elderly.task;

import com.elderly.entity.ElderlyUser;
import com.elderly.entity.FamilyBind;
import com.elderly.entity.HealthNotify;
import com.elderly.entity.MedicationReminder;
import com.elderly.mapper.FamilyBindMapper;
import com.elderly.mapper.HealthNotifyMapper;
import com.elderly.mapper.MedicationReminderMapper;
import com.elderly.service.ElderlyUserService;
import com.elderly.service.MedicationTakenLogService;
import com.elderly.util.OrderCooldownUtil;
import com.elderly.ws.OrderWebSocketHandler;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 漏服巡检任务 —— 到点后一段时间仍未确认服药，则通知家属/护工。
 *
 * 背景：适老化评审提出"如果到了时间未服药，应触发通知家属的预警"。
 * 之前服药状态只在小程序本地，后端无从判断，因此这里先补上"打卡落库"，
 * 再由本任务巡检漏服并走既有通知通道（health_notify 落库 + WebSocket 实时推送）。
 *
 * 规则：
 *   1) 到点后宽限 2 小时（老人可能忘看、或正在路上），超过才判定漏服；
 *   2) 同一天同一条药只通知一次（Redis 幂等键 + 进程内兜底）；
 *   3) 只处理今天，避免历史遗留计划天天报警；
 *   4) 通知文案用大白话，不出现"dose_time"这类字段名。
 */
@Component
public class MedicationMissedTask {

    private static final Logger log = LoggerFactory.getLogger(MedicationMissedTask.class);

    /** 到点后宽限多久判定漏服 */
    private static final Duration GRACE = Duration.ofHours(2);

    /** 幂等键 TTL：到当天结束为止 */
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    @Resource
    private MedicationReminderMapper reminderMapper;
    @Resource
    private MedicationTakenLogService takenLogService;
    @Resource
    private ElderlyUserService userService;
    @Resource
    private FamilyBindMapper familyBindMapper;
    @Resource
    private HealthNotifyMapper healthNotifyMapper;
    @Resource
    private OrderWebSocketHandler webSocketHandler;
    @Resource
    private OrderCooldownUtil cooldownUtil;

    /** 每 15 分钟巡检一次 */
    @Scheduled(cron = "0 */15 * * * ?")
    public void checkMissedMedication() {
        runCheck();
    }

    /**
     * 执行一次漏服巡检。
     * @return 本次实际通知出去的通知条数
     */
    public int runCheck() {
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();
        int notified = 0;
        try {
            List<MedicationReminder> list = reminderMapper.selectAllEnabled();
            if (list == null || list.isEmpty()) return 0;

            for (MedicationReminder r : list) {
                try {
                    if (r == null || r.getUserId() == null || r.getDoseTime() == null) continue;
                    // 还没到点
                    if (now.isBefore(r.getDoseTime())) continue;
                    // 还在宽限期
                    if (now.isBefore(r.getDoseTime().plus(GRACE))) continue;

                    String drug = r.getDrugName() == null ? "" : r.getDrugName();
                    // 当天已打卡 → 没漏服
                    if (takenLogService.isTaken(r.getUserId(), r.getDoseTime(), drug, today)) continue;

                    // 同一天同一条药只通知一次
                    String idemKey = "medmiss:" + r.getUserId() + ":" + today + ":" + r.getDoseTime() + ":" + drug;
                    if (!cooldownUtil.tryAcquireKey(idemKey, Duration.ofHours(6))) continue;

                    if (notifyFamily(r, today)) notified++;
                } catch (Exception ex) {
                    // 单条失败不影响其它老人（例如打卡表还没建）
                    log.warn("漏服判定失败，跳过该条: {}", ex.getMessage());
                }
            }
            if (notified > 0) {
               	log.info("漏服巡检完成：本次通知家属 {} 条", notified);
           	}
       	} catch (Exception e) {
           	log.error("漏服巡检任务异常: {}", e.getMessage(), e);
       	}
		return notified;
	}

    /**
     * 通知该老人的家属/护工。
     * @return 是否至少通知到一个人
     */
    private boolean notifyFamily(MedicationReminder r, LocalDate today) {
        List<FamilyBind> binds = familyBindMapper.selectEnabledByElderlyUserId(r.getUserId());
        if (binds == null || binds.isEmpty()) return false;

        ElderlyUser u = null;
        try {
            u = userService.getById(r.getUserId());
        } catch (Exception ignored) {
        }
        String name = (u != null && u.getName() != null && !u.getName().isBlank()) ? u.getName() : "老人";
        String drug = (r.getDrugName() == null || r.getDrugName().isBlank()) ? "药" : r.getDrugName();
        String timeText = r.getDoseTime().format(TIME_FMT);

        String title = "服药提醒 · " + name;
        String content = name + " " + timeText + " 的" + drug + "到现在还没确认服用，已经过了2个小时，麻烦帮忙提醒她一下。";

        boolean sent = false;
        for (FamilyBind b : binds) {
            if (b == null) continue;
            HealthNotify n = new HealthNotify();
            n.setElderlyUserId(r.getUserId());
            n.setElderlyName(name);
            n.setReceiverRole(b.getRole());
            n.setReceiverName(b.getName());
            n.setReceiverOpenId(b.getOpenId());
            n.setCaregiverId(b.getCaregiverId());
            // medium=黄色提醒：家属端看到"需要关注"但不至于像红色告警那么吓人
            n.setRiskLevel("medium");
            n.setTitle(title);
            n.setContent(content);
            n.setStatus("unread");
            try {
                healthNotifyMapper.insert(n);
                webSocketHandler.pushHealthAlert(n);
                sent = true;
            } catch (Exception ex) {
                log.warn("漏服通知写入失败 userId={}: {}", r.getUserId(), ex.getMessage());
            }
        }
        return sent;
    }
}

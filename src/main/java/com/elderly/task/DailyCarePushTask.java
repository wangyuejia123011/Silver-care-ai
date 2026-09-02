package com.elderly.task;

import com.elderly.entity.ElderlyUser;
import com.elderly.entity.FamilyBind;
import com.elderly.entity.HealthNotify;
import com.elderly.entity.HealthRecord;
import com.elderly.mapper.FamilyBindMapper;
import com.elderly.mapper.HealthNotifyMapper;
import com.elderly.service.DailyCareService;
import com.elderly.service.ElderlyUserService;
import com.elderly.service.HealthRecordService;
import com.elderly.ws.OrderWebSocketHandler;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 定时关怀推送任务 —— 每日早晨为每位活跃老人生成"今日守护"关怀，
 * 并主动推送给其绑定的家属 / 护工（通过既有的健康通知通道）。
 *
 * 设计要点：
 *   1) 每日 07:30 触发（cron: 秒 分 时 日 月 周），可根据运营需要调整；
 *   2) 生成结果写入 Redis/本地缓存（30 分钟），老人打开首页即可秒看，无需重复调用大模型；
 *   3) 同时向绑定家属推送一条"今日关怀"通知（riskLevel=low），实现"定时推送"；
 *   4) 同一天同一老人只推送一次（进程内按 userId+date 去重，任务每天仅跑一次足够）。
 */
@Component
public class DailyCarePushTask {

    private static final Logger log = LoggerFactory.getLogger(DailyCarePushTask.class);

    /** 当天已推送集合（进程内去重，任务每天只跑一次） */
    private static final ConcurrentHashMap<String, Boolean> PUSHED = new ConcurrentHashMap<>();

    @Resource
    private DailyCareService dailyCareService;
    @Resource
    private ElderlyUserService userService;
    @Resource
    private HealthRecordService healthRecordService;
    @Resource
    private FamilyBindMapper familyBindMapper;
    @Resource
    private HealthNotifyMapper healthNotifyMapper;
    @Resource
    private OrderWebSocketHandler webSocketHandler;

    @Scheduled(cron = "0 30 7 * * ?")
    public void pushDailyCare() {
        try {
            LocalDate today = LocalDate.now();
            List<ElderlyUser> users = userService.listAll();
            int ok = 0;
            for (ElderlyUser u : users) {
                if (u == null || u.getId() == null) continue;
                try {
                    List<HealthRecord> todayH = healthRecordService.listTodayByUserId(u.getId());
                    List<HealthRecord> recent = healthRecordService.listRecentByUserId(u.getId(), 7);
                    Map<String, Object> care = dailyCareService.generate(u, todayH, recent, today, false);
                    pushToFamily(u, care);
                    ok++;
                } catch (Exception ex) {
                    log.warn("今日关怀生成/推送失败 userId={}: {}", u.getId(), ex.getMessage());
                }
            }
            log.info("今日关怀定时任务完成：成功处理 {} / 共 {} 位老人", ok, users.size());
        } catch (Exception e) {
            log.error("今日关怀定时任务异常: {}", e.getMessage(), e);
        }
    }

    private void pushToFamily(ElderlyUser u, Map<String, Object> care) {
        String dayKey = "care:" + u.getId() + ":" + LocalDate.now();
        if (PUSHED.putIfAbsent(dayKey, Boolean.TRUE) != null) {
            return; // 当天已推送
        }

        String text = care == null ? "" : String.valueOf(care.getOrDefault("text", ""));
        List<FamilyBind> binds = familyBindMapper.selectEnabledByElderlyUserId(u.getId());
        if (binds == null || binds.isEmpty()) {
            return;
        }
        String title = "今日关怀 · " + (u.getName() == null ? "老人" : u.getName());
        for (FamilyBind b : binds) {
            if (b == null) continue;
            HealthNotify n = new HealthNotify();
            n.setElderlyUserId(u.getId());
            n.setElderlyName(u.getName());
            n.setReceiverRole(b.getRole());
            n.setReceiverName(b.getName());
            n.setReceiverOpenId(b.getOpenId());
            n.setCaregiverId(b.getCaregiverId());
            n.setRiskLevel("low");
            n.setTitle(title);
            n.setContent(text);
            n.setStatus("unread");
            try {
                healthNotifyMapper.insert(n);
                webSocketHandler.pushHealthAlert(n);
            } catch (Exception ex) {
                log.warn("今日关怀推送写入失败 userId={}: {}", u.getId(), ex.getMessage());
            }
        }
    }
}

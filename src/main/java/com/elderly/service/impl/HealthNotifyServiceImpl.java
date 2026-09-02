package com.elderly.service.impl;

import com.elderly.entity.ElderlyUser;
import com.elderly.entity.FamilyBind;
import com.elderly.entity.HealthNotify;
import com.elderly.entity.HealthRecord;
import com.elderly.mapper.FamilyBindMapper;
import com.elderly.mapper.HealthNotifyMapper;
import com.elderly.service.ElderlyUserService;
import com.elderly.service.HealthNotifyService;
import com.elderly.service.WxSubscribeService;
import com.elderly.ws.OrderWebSocketHandler;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class HealthNotifyServiceImpl implements HealthNotifyService {

    private static final Logger log = LoggerFactory.getLogger(HealthNotifyServiceImpl.class);

    @Resource
    private FamilyBindMapper familyBindMapper;
    @Resource
    private HealthNotifyMapper healthNotifyMapper;
    @Resource
    private ElderlyUserService userService;
    @Resource
    private OrderWebSocketHandler webSocketHandler;
    @Resource
    private WxSubscribeService wxSubscribeService;

    @Override
    public List<HealthNotify> notifyBound(HealthRecord record, String voiceText) {
        List<HealthNotify> sent = new ArrayList<>();
        if (record == null || record.getUserId() == null) {
            return sent;
        }
        String risk = record.getRiskLevel();
        if (!"medium".equals(risk) && !"high".equals(risk)) {
            return sent;
        }

        ElderlyUser elderly = userService.getById(record.getUserId());
        String elderlyName = record.getElderlyName();
        if (elderlyName == null && elderly != null) {
            elderlyName = elderly.getName();
        }

        List<FamilyBind> binds = familyBindMapper.selectEnabledByElderlyUserId(record.getUserId());
        if (binds == null || binds.isEmpty()) {
            binds = fallbackEmergencyContact(elderly);
        }

        String title = "high".equals(risk) ? "【高危】健康预警，请立即查看" : "【关注】健康指标异常";
        String content = buildContent(elderlyName, record, voiceText);

        for (FamilyBind bind : binds) {
            if (bind == null) continue;
            HealthNotify notify = new HealthNotify();
            notify.setElderlyUserId(record.getUserId());
            notify.setElderlyName(elderlyName);
            notify.setRecordId(record.getId());
            notify.setBindId(bind.getId());
            notify.setReceiverRole(bind.getRole());
            notify.setReceiverName(bind.getName());
            notify.setReceiverOpenId(bind.getOpenId());
            notify.setCaregiverId(bind.getCaregiverId());
            notify.setRiskLevel(risk);
            notify.setTitle(title);
            notify.setContent(content);
            notify.setVoiceText(voiceText);
            notify.setStatus("unread");
            try {
                healthNotifyMapper.insert(notify);
                webSocketHandler.pushHealthAlert(notify);

                // 微信订阅消息推送（仅 openId 非空时尝试；失败不影响主流程）
                if (bind.getOpenId() != null && !bind.getOpenId().isBlank()) {
                    try {
                        wxSubscribeService.sendHealthAbnormal(
                                bind.getOpenId(),
                                record.getSystolicPressure() == null ? "-" : String.valueOf(record.getSystolicPressure()),
                                record.getDiastolicPressure() == null ? "-" : String.valueOf(record.getDiastolicPressure()),
                                record.getHeartRate() == null ? "-" : String.valueOf(record.getHeartRate()),
                                record.getBloodSugar() == null ? "-" : String.valueOf(record.getBloodSugar()),
                                record.getTemperature() == null ? "-" : String.valueOf(record.getTemperature())
                        );
                    } catch (Exception e) {
                        log.warn("微信订阅消息推送失败: receiver={}, {}", bind.getName(), e.getMessage());
                    }
                }

                sent.add(notify);
                log.info("已发送健康通知: receiver={}, role={}, recordId={}",
                        bind.getName(), bind.getRole(), record.getId());
            } catch (Exception e) {
                log.warn("健康通知发送失败: {}", e.getMessage());
            }
        }
        return sent;
    }

    @Override
    public Map<String, Object> summarize(List<HealthNotify> notifies) {
        Map<String, Object> map = new HashMap<>();
        map.put("count", notifies == null ? 0 : notifies.size());
        List<Map<String, Object>> receivers = new ArrayList<>();
        if (notifies != null) {
            for (HealthNotify n : notifies) {
                Map<String, Object> item = new HashMap<>();
                item.put("id", n.getId());
                item.put("name", n.getReceiverName());
                item.put("role", n.getReceiverRole());
                item.put("recordId", n.getRecordId());
                receivers.add(item);
            }
        }
        map.put("receivers", receivers);
        return map;
    }

    @Override
    public List<HealthNotify> inboxByOpenId(String openId) {
        return healthNotifyMapper.selectByReceiverOpenId(openId);
    }

    @Override
    public List<HealthNotify> inboxByCaregiverId(Long caregiverId) {
        return healthNotifyMapper.selectByCaregiverId(caregiverId);
    }

    @Override
    public HealthNotify getById(Long id) {
        return healthNotifyMapper.selectById(id);
    }

    @Override
    public boolean markRead(Long id) {
        return healthNotifyMapper.markRead(id) > 0;
    }

    @Override
    public int countUnreadByOpenId(String openId) {
        return healthNotifyMapper.countUnreadByOpenId(openId);
    }

    @Override
    public int countUnreadByCaregiverId(Long caregiverId) {
        return healthNotifyMapper.countUnreadByCaregiverId(caregiverId);
    }

    private String buildContent(String elderlyName, HealthRecord record, String voiceText) {
        StringBuilder sb = new StringBuilder();
        sb.append(elderlyName == null ? "老人" : elderlyName).append("语音上报健康情况。");
        if (record.getSystolicPressure() != null) {
            sb.append("血压").append(record.getSystolicPressure()).append("/")
                    .append(record.getDiastolicPressure() == null ? "-" : record.getDiastolicPressure()).append("。");
        }
        if (record.getHeartRate() != null) {
            sb.append("心率").append(record.getHeartRate()).append("。");
        }
        if (record.getBloodSugar() != null) {
            sb.append("血糖").append(record.getBloodSugar()).append("。");
        }
        if (record.getTemperature() != null) {
            sb.append("体温").append(record.getTemperature()).append("。");
        }
        if (voiceText != null && !voiceText.isBlank()) {
            sb.append("原话：").append(voiceText);
        }
        return sb.toString();
    }

    /** 未绑定人员时，用档案里的紧急联系人兜底 */
    private List<FamilyBind> fallbackEmergencyContact(ElderlyUser elderly) {
        List<FamilyBind> list = new ArrayList<>();
        if (elderly == null) return list;
        if (elderly.getEmergencyContact() == null && elderly.getEmergencyPhone() == null) {
            return list;
        }
        FamilyBind bind = new FamilyBind();
        bind.setElderlyUserId(elderly.getId());
        bind.setRole("family");
        bind.setName(elderly.getEmergencyContact() == null ? "紧急联系人" : elderly.getEmergencyContact());
        bind.setPhone(elderly.getEmergencyPhone());
        bind.setNotifyEnabled(1);
        list.add(bind);
        log.info("未找到绑定人员，已使用紧急联系人兜底: {}", bind.getName());
        return list;
    }
}

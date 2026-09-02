package com.elderly.service;

import com.elderly.entity.FamilyBind;
import com.elderly.entity.HealthNotify;
import com.elderly.entity.HealthRecord;

import java.util.List;
import java.util.Map;

public interface HealthNotifyService {

    /**
     * 风险达到通知条件时：查绑定家属/护工 → 落库 → WebSocket 推送。
     */
    List<HealthNotify> notifyBound(HealthRecord record, String voiceText);

    Map<String, Object> summarize(List<HealthNotify> notifies);

    /** 按接收人 openId（家属）查询通知收件箱 */
    List<HealthNotify> inboxByOpenId(String openId);

    /** 按护工ID查询通知收件箱 */
    List<HealthNotify> inboxByCaregiverId(Long caregiverId);

    /** 根据ID查询通知详情 */
    HealthNotify getById(Long id);

    /** 标记通知为已读 */
    boolean markRead(Long id);

    /** 统计家属未读通知数 */
    int countUnreadByOpenId(String openId);

    /** 统计护工未读通知数 */
    int countUnreadByCaregiverId(Long caregiverId);
}

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

    /** 按老人ID查询通知收件箱（含其绑定家属/护工的全部通知，用于老人端通知中心） */
    List<HealthNotify> inboxByElderlyUserId(Long elderlyUserId);

    /** 统计某老人绑定关系下的未读通知数 */
    int countUnreadByElderlyUserId(Long elderlyUserId);

    /**
     * 按范围删除通知：优先 openId，其次 caregiverId，最后 elderlyUserId。
     * 三者均为空时返回 0（防止误删全表）。
     */
    int clearByScope(String openId, Long caregiverId, Long elderlyUserId);

    /** 根据ID查询通知详情 */
    HealthNotify getById(Long id);

    /** 标记通知为已读 */
    boolean markRead(Long id);

    /** 统计家属未读通知数 */
    int countUnreadByOpenId(String openId);

    /** 统计护工未读通知数 */
    int countUnreadByCaregiverId(Long caregiverId);
}

package com.elderly.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 健康风险通知
 */
@Data
public class HealthNotify {

    private Long id;
    private Long elderlyUserId;
    private String elderlyName;
    private Long recordId;
    private Long bindId;
    private String receiverRole;
    private String receiverName;
    private String receiverOpenId;
    private Long caregiverId;
    private String riskLevel;
    private String title;
    private String content;
    private String voiceText;
    /** unread / read */
    private String status;
    private LocalDateTime createTime;
}

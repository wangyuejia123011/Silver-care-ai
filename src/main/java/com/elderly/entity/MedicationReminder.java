package com.elderly.entity;

import lombok.Data;

import java.time.LocalTime;

/**
 * 老人服药提醒计划
 */
@Data
public class MedicationReminder {

    private Long id;

    /** 关联老人用户ID */
    private Long userId;

    /** 药名，如 降压药 / 降糖药 */
    private String drugName;

    /** 服药时间，如 15:00:00 */
    private LocalTime doseTime;

    /** 剂量，如 1片 / 半袋 */
    private String dosage;

    /** 备注 */
    private String note;

    /** 是否启用：1-是 0-否 */
    private Integer enabled;

    private java.time.LocalDateTime createTime;
}

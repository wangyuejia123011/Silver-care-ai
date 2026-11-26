package com.elderly.entity;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 老人服药打卡记录。
 *
 * 用途：让"到了时间没吃药 → 通知家属"成为可能。
 * 之前服药状态只存在小程序本地（silver_med_taken），后端完全不知道老人吃没吃，
 * 家属端也就无法知道是否漏服。这里把打卡落到服务端，作为漏服巡检的数据源。
 */
@Data
public class MedicationTakenLog {

    private Long id;

    /** 关联老人用户ID */
    private Long userId;

    /** 服药时间（对应 medication_reminder.dose_time，如 08:00:00） */
    private LocalTime doseTime;

    /** 药名（对应 medication_reminder.drug_name） */
    private String drugName;

    /** 打卡日期 */
    private LocalDate takenDate;

    /** 实际打卡时刻 */
    private LocalDateTime takenAt;

    /** 打卡来源：elder=老人自己打勾 / family=家属代填 */
    private String source;

    private LocalDateTime createTime;
}

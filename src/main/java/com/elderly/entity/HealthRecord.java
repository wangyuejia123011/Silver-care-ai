package com.elderly.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 健康记录表 —— 记录老人每次上报的健康指标
 */
@Data
public class HealthRecord {

    private Long id;

    /** 关联老人用户ID */
    private Long userId;

    /** 老人姓名（冗余，方便查询） */
    private String elderlyName;

    /** 年龄 */
    private Integer age;

    /** 收缩压（高压） */
    private Integer systolicPressure;

    /** 舒张压（低压） */
    private Integer diastolicPressure;

    /** 心率 */
    private Integer heartRate;

    /** 血糖 */
    private Double bloodSugar;

    /** 体温 */
    private Double temperature;

    /** 记录来源：voice(语音) / manual(手动录入) */
    private String source;

    /** 语音识别原文 */
    private String voiceText;

    /** 风险等级：low / medium / high */
    private String riskLevel;

    /** AI生成的健康建议 */
    private String aiAdvice;

    /** 是否高危：0-正常 1-高危 */
    private Integer isAlert;

    /** 记录时间 */
    private LocalDateTime recordTime;

    /** 创建时间 */
    private LocalDateTime createTime;
}

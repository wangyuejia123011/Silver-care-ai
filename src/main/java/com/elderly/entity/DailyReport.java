package com.elderly.entity;

import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 社区养老日报表 —— 定时任务每日生成
 */
@Data
public class DailyReport {

    private Long id;

    /** 报告日期 */
    private LocalDate reportDate;

    /** 当日健康记录总数 */
    private Integer healthRecordCount;

    /** 当日高危老人数量 */
    private Integer highRiskCount;

    /** 当日反诈拦截条数 */
    private Integer fraudBlockCount;

    /** 当日工单数量 */
    private Integer orderCount;

    /** AI生成的日报摘要 */
    private String summary;

    /** 创建时间 */
    private LocalDateTime createTime;
}

package com.elderly.dto;

import lombok.Data;

/**
 * 护工评分聚合结果（由 care_order 表的评分实时汇总）
 * 用于把「所有用户对该护工的评价」加权平均后回写到 caregiver.rating / rating_count。
 */
@Data
public class RatingAgg {

    /** 有效评价条数（rating 非空的工单数） */
    private Integer count;

    /** 平均分，保留一位小数；无评价时为 null */
    private Double avg;
}

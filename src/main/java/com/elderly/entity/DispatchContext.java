package com.elderly.entity;

import lombok.Data;

/**
 * 场景化派单上下文：把"老人需求 + 健康数据 + 老人画像"翻译成可调度的约束条件。
 *
 * 由 OrderDispatchAgent.classify() 根据需求文本与老人性别/健康情况判定，
 * 再交给 CaregiverService.matchBestCaregiver() 做加权匹配。
 */
@Data
public class DispatchContext {

    /** 场景分类：HEALTH(健康服务) / BATH(助浴等需性别匹配) / DAILY(日常照料) / EMERGENCY(紧急) */
    private String category;

    /** 所需标准技能标签（如"急救""助浴""康复""保洁"），null 表示不限 */
    private String skill;

    /** 是否需要护工携带医疗设备上门（如血压计/体温计） */
    private boolean needDevice;

    /** 要求护工性别（助浴等场景需与老人性别一致），null 表示不限 */
    private String requireGender;

    /** 是否紧急工单 */
    private boolean emergency;
}

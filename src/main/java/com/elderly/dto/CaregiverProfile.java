package com.elderly.dto;

import com.elderly.entity.Caregiver;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 护工详细信息（详情页面用的聚合数据）
 * 护工注册后点进个人主页，展示：基础档案 + 技能 + 服务统计
 */
@Data
public class CaregiverProfile {

    /** 护工基础档案（含头像、简介、评分等扩展字段） */
    private Caregiver caregiver;

    /** 技能标签数组（把 skills 的 JSON 串拆开，方便前端直接渲染） */
    private List<String> skills;

    /** 服务统计：今日工单/待完成/已完成/总接单 */
    private Map<String, Object> stats;

    /** 个人主页展示状态文案：在岗 / 休息中 */
    private String statusText;
}

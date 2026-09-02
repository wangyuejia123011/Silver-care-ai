package com.elderly.util;

import com.elderly.entity.HealthRecord;

/**
 * 统一健康风险评估工具类
 *
 * 集中管理所有健康指标的阈值和分级逻辑，消除 HealthAgent 和 HealthRecordServiceImpl
 * 中的重复代码，确保风险评级标准一致。
 *
 * 风险分级标准：
 *   high   —— 达到高危阈值，需立即通知家属/护工并自动生成工单
 *   medium —— 达到关注阈值，需通知家属/护工
 *   low    —— 指标正常，无需通知
 */
public class HealthRiskEvaluator {

    // ==================== 血压阈值 ====================
    /** 收缩压高危阈值（高于此值为高危） */
    public static final int SYS_HIGH = 180;
    /** 舒张压高危阈值 */
    public static final int DIA_HIGH = 120;
    /** 收缩压中危阈值（高于此值为中危，低于此值且≥90为正常） */
    public static final int SYS_MEDIUM = 140;
    /** 收缩压偏低阈值（低于此值为中危） */
    public static final int SYS_LOW = 90;
    /** 舒张压中危阈值 */
    public static final int DIA_MEDIUM = 90;

    // ==================== 心率阈值 ====================
    /** 心率高危上限 */
    public static final int HR_HIGH_MAX = 120;
    /** 心率高危下限 */
    public static final int HR_HIGH_MIN = 50;
    /** 心率中危上限 */
    public static final int HR_MEDIUM_MAX = 100;
    /** 心率中危下限 */
    public static final int HR_MEDIUM_MIN = 60;

    // ==================== 血糖阈值 ====================
    /** 血糖高危上限（mmol/L） */
    public static final double BS_HIGH_MAX = 11.1;
    /** 血糖高危下限 */
    public static final double BS_HIGH_MIN = 3.9;
    /** 血糖中危上限 */
    public static final double BS_MEDIUM_MAX = 7.0;
    /** 血糖中危下限 */
    public static final double BS_MEDIUM_MIN = 4.4;

    // ==================== 体温阈值 ====================
    /** 体温高危阈值（℃） */
    public static final double TEMP_HIGH = 39.0;
    /** 体温中危阈值 */
    public static final double TEMP_MEDIUM = 37.5;

    // ==================== 高危症状关键词 ====================
    public static final String[] HIGH_RISK_SYMPTOMS = {
            "胸痛", "心绞痛", "心梗",
            "摔倒", "晕倒", "昏迷", "晕厥",
            "呼吸困难", "喘不上气", "窒息",
            "抽搐", "意识不清", "意识模糊", "口吐白沫",
            "大出血", "呕血", "咯血"
    };

    /**
     * 评估健康记录的风险等级
     *
     * @param record 健康记录（指标可为null，null表示未测量该指标）
     * @return 风险等级：high / medium / low
     */
    public static String evaluateRisk(HealthRecord record) {
        if (record == null) return "low";

        // 1. 高危判断（任一指标达到高危阈值即为high）
        if (isHighRisk(record)) return "high";

        // 2. 中危判断（任一指标达到中危阈值即为medium）
        if (isMediumRisk(record)) return "medium";

        return "low";
    }

    /**
     * 判断是否为高危（isAlert = 1）
     */
    public static boolean isHighRisk(HealthRecord record) {
        if (record == null) return false;

        // 血压
        if (record.getSystolicPressure() != null && record.getSystolicPressure() > SYS_HIGH) return true;
        if (record.getDiastolicPressure() != null && record.getDiastolicPressure() > DIA_HIGH) return true;

        // 心率
        if (record.getHeartRate() != null
                && (record.getHeartRate() > HR_HIGH_MAX || record.getHeartRate() < HR_HIGH_MIN)) return true;

        // 血糖
        if (record.getBloodSugar() != null
                && (record.getBloodSugar() > BS_HIGH_MAX || record.getBloodSugar() < BS_HIGH_MIN)) return true;

        // 体温
        if (record.getTemperature() != null && record.getTemperature() >= TEMP_HIGH) return true;

        // 高危症状（语音原文中包含）
        if (record.getVoiceText() != null && containsHighRiskSymptom(record.getVoiceText())) return true;

        return false;
    }

    /**
     * 判断是否为中危（未达到高危但达到中危阈值）
     */
    public static boolean isMediumRisk(HealthRecord record) {
        if (record == null) return false;

        // 血压
        if (record.getSystolicPressure() != null
                && (record.getSystolicPressure() > SYS_MEDIUM || record.getSystolicPressure() < SYS_LOW)) return true;
        if (record.getDiastolicPressure() != null && record.getDiastolicPressure() > DIA_MEDIUM) return true;

        // 心率
        if (record.getHeartRate() != null
                && (record.getHeartRate() > HR_MEDIUM_MAX || record.getHeartRate() < HR_MEDIUM_MIN)) return true;

        // 血糖
        if (record.getBloodSugar() != null
                && (record.getBloodSugar() > BS_MEDIUM_MAX || record.getBloodSugar() < BS_MEDIUM_MIN)) return true;

        // 体温
        if (record.getTemperature() != null && record.getTemperature() >= TEMP_MEDIUM) return true;

        return false;
    }

    /**
     * 文本中是否包含高危症状关键词
     */
    public static boolean containsHighRiskSymptom(String text) {
        if (text == null || text.isBlank()) return false;
        for (String symptom : HIGH_RISK_SYMPTOMS) {
            if (text.contains(symptom)) return true;
        }
        return false;
    }

    /**
     * 生成高危警告文案（用于 isAlert=1 时的固定提示）
     */
    public static String buildHighRiskWarning(HealthRecord record) {
        StringBuilder sb = new StringBuilder("【高危预警】");
        if (record == null) {
            sb.append("指标异常，请立即休息并联系护工或就医！");
            return sb.toString();
        }

        if (record.getSystolicPressure() != null && record.getSystolicPressure() > SYS_HIGH) {
            sb.append("血压过高（").append(record.getSystolicPressure());
            if (record.getDiastolicPressure() != null) sb.append("/").append(record.getDiastolicPressure());
            sb.append("），");
        }
        if (record.getHeartRate() != null && (record.getHeartRate() > HR_HIGH_MAX || record.getHeartRate() < HR_HIGH_MIN)) {
            sb.append("心率异常（").append(record.getHeartRate()).append("次/分），");
        }
        if (record.getBloodSugar() != null && (record.getBloodSugar() > BS_HIGH_MAX || record.getBloodSugar() < BS_HIGH_MIN)) {
            sb.append("血糖异常（").append(record.getBloodSugar()).append("mmol/L），");
        }
        if (record.getTemperature() != null && record.getTemperature() >= TEMP_HIGH) {
            sb.append("高烧（").append(record.getTemperature()).append("℃），");
        }
        sb.append("请立即休息并联系护工或就医！");
        return sb.toString();
    }

    /**
     * 获取所有阈值的描述（可用于前端展示或接口返回）
     */
    public static java.util.Map<String, Object> getThresholds() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("systolicPressure", java.util.Map.of("high", SYS_HIGH, "medium", SYS_MEDIUM, "low", SYS_LOW));
        map.put("diastolicPressure", java.util.Map.of("high", DIA_HIGH, "medium", DIA_MEDIUM));
        map.put("heartRate", java.util.Map.of("highMax", HR_HIGH_MAX, "highMin", HR_HIGH_MIN,
                "mediumMax", HR_MEDIUM_MAX, "mediumMin", HR_MEDIUM_MIN));
        map.put("bloodSugar", java.util.Map.of("highMax", BS_HIGH_MAX, "highMin", BS_HIGH_MIN,
                "mediumMax", BS_MEDIUM_MAX, "mediumMin", BS_MEDIUM_MIN));
        map.put("temperature", java.util.Map.of("high", TEMP_HIGH, "medium", TEMP_MEDIUM));
        return map;
    }
}

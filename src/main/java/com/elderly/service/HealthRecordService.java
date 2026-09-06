package com.elderly.service;

import com.elderly.entity.HealthRecord;
import java.time.LocalDateTime;
import java.util.List;

public interface HealthRecordService {

    /** 保存健康记录（含AI建议生成） */
    HealthRecord save(HealthRecord record);

    /** 直接落库（语音链路AI建议已流式生成，跳过重复AI调用） */
    HealthRecord saveRaw(HealthRecord record);

    /** 查询用户健康记录列表 */
    List<HealthRecord> listByUserId(Long userId);

    /** 查询用户最近一条健康记录 */
    HealthRecord getLatest(Long userId);

    /** 查询用户最近 N 条健康记录（用于"今日守护"趋势分析） */
    List<HealthRecord> listRecentByUserId(Long userId, int limit);

    /** 查询用户当日健康记录（用于"今日守护"聚合） */
    List<HealthRecord> listTodayByUserId(Long userId);

    /** 根据ID查询 */
    HealthRecord getById(Long id);

    /** 查询某时间段内健康记录 */
    List<HealthRecord> listByTimeRange(LocalDateTime start, LocalDateTime end);

    /** 统计某时间段内健康记录数 */
    int countByTimeRange(LocalDateTime start, LocalDateTime end);

    /** 统计某时间段内高危记录数 */
    int countHighRisk(LocalDateTime start, LocalDateTime end);

    /** 清空用户健康记录 */
    int clearByUserId(Long userId);
}

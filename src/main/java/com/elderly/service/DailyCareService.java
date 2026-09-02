package com.elderly.service;

import com.elderly.entity.ElderlyUser;
import com.elderly.entity.HealthRecord;

import java.time.LocalDate;
import java.util.List;

/**
 * 「今日守护」智能关怀：根据老人当日健康记录、近期趋势、档案信息，
 * 调用 LLM 生成一段温暖、具体的关怀语（用于首页卡片展示）。
 *
 * 设计要点：
 *   1) LLM 按需生成 —— 不是每次进入首页都问大模型，而是缓存 30 分钟；
 *   2) Redis 不可用时降级 —— 默认使用本地内存缓存（ConcurrentHashMap）；
 *   3) 数据源：当日 health_record（聚合指标）、近 7 天趋势（用于稳定/异常判断）、档案（年龄/病史/地址）；
 *   4) 不强制接入天气 API —— 默认嵌入「温和提醒多饮水」「按时吃药」等普适关怀；
 *   5) 输出固定字段：text（主文案）、bullets（要点列表）、weatherTip（天气/服药提醒）、source（数据来源说明）。
 */
public interface DailyCareService {

    /**
     * 生成指定老人今日的智能关怀文案
     *
     * @param user 老人档案
     * @param todayHealth 今日健康记录（可有 0~N 条）
     * @param recentHealth 近期健康记录（用于趋势判断，限量）
     * @param today 业务日期（一般传 LocalDate.now()，便于缓存键）
     * @param forceRefresh true 时忽略缓存强制重新生成
     */
    java.util.Map<String, Object> generate(ElderlyUser user,
                                           List<HealthRecord> todayHealth,
                                           List<HealthRecord> recentHealth,
                                           LocalDate today,
                                           boolean forceRefresh);
}

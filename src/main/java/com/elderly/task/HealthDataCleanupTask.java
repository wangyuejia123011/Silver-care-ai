package com.elderly.task;

import com.elderly.service.HealthRecordService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时清理任务 —— 每日凌晨清除「健康管理」板块前一天的数据，让新的一天从空白开始。
 *
 * 安全设计（重要）：
 *   1) 仅当数据源指向【开发/测试库】时才真正执行删除；连到其它库（含生产）时自动跳过，
 *      从结构上避免误删生产数据。开发库主机由 DEV_DB_HOST 配置（默认 192.168.38.134）。
 *   2) 另有总开关 health.daily-clear.enabled（默认 false，需显式开启），避免误部署即触发。
 *   3) 删除口径为“create_time < 当天 00:00”，即清除今天之前的所有健康记录，
 *      保留当天（凌晨时刻通常为空）数据，实现“每日重新开始”。
 */
@Component
public class HealthDataCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(HealthDataCleanupTask.class);

    @Resource
    private HealthRecordService healthRecordService;

    /** 数据源 URL，用于判断当前连接的是否为开发/测试库 */
    @Value("${spring.datasource.url:}")
    private String datasourceUrl;

    /** 开发/测试库主机标识：URL 中包含该主机才允许清理 */
    @Value("${health.daily-clear.dev-db-host:192.168.38.134}")
    private String devDbHost;

    /** 总开关：默认关闭，必须显式开启 */
    @Value("${health.daily-clear.enabled:false}")
    private boolean enabled;

    /**
     * 每日凌晨 00:00（上海时区）执行清理。
     * cron: 秒 分 时 日 月 周
     */
    @Scheduled(cron = "0 0 0 * * ?", zone = "Asia/Shanghai")
    public void clearYesterdayHealthData() {
        if (!enabled) {
            log.info("[健康管理每日清理] 未启用（health.daily-clear.enabled=false），跳过");
            return;
        }
        if (datasourceUrl == null || !datasourceUrl.contains(devDbHost)) {
            log.warn("[健康管理每日清理] 当前数据源 {} 非开发/测试库（期望包含 {}），为安全起见跳过删除",
                    datasourceUrl, devDbHost);
            return;
        }
        try {
            int deleted = healthRecordService.clearBeforeToday();
            log.info("[健康管理每日清理] 已完成：清除今天之前的健康记录 {} 条，新的一天从空白开始", deleted);
        } catch (Exception e) {
            log.error("[健康管理每日清理] 执行失败: {}", e.getMessage(), e);
        }
    }
}

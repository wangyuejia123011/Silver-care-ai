package com.elderly.task;

import com.elderly.entity.DailyReport;
import com.elderly.service.DailyReportService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 定时任务 —— 每日自动生成社区养老日报
 */
@Component
public class DailyReportTask {

    private static final Logger log = LoggerFactory.getLogger(DailyReportTask.class);

    @Resource
    private DailyReportService dailyReportService;

    /**
     * 每天晚上23:00自动生成当日日报（文档2.0：每日23:00定时任务）
     * cron: 秒 分 时 日 月 周
     */
    @Scheduled(cron = "0 0 23 * * ?")
    public void generateDailyReport() {
        try {
            LocalDate today = LocalDate.now();
            log.info("开始生成日报: {}", today);
            DailyReport report = dailyReportService.generateDailyReport(today);
            log.info("日报生成完成: {}", report.getSummary());
        } catch (Exception e) {
            log.error("日报生成失败: {}", e.getMessage(), e);
        }
    }
}

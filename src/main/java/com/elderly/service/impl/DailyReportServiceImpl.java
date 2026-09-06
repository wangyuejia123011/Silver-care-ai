package com.elderly.service.impl;

import com.elderly.entity.Caregiver;
import com.elderly.entity.DailyReport;
import com.elderly.mapper.CaregiverMapper;
import com.elderly.mapper.DailyReportMapper;
import com.elderly.service.DailyReportService;
import com.elderly.service.HealthRecordService;
import com.elderly.service.FraudAlertService;
import com.elderly.service.CareOrderService;
import com.elderly.util.LlmUtil;
import com.elderly.util.PromptUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 社区养老日报服务实现。
 *
 * 优化点：
 * 1. 生成日报时即使 AI 不可用或数据库异常，也返回兜底摘要，绝不抛异常导致前端“系统繁忙”。
 * 2. 同一天多次生成时更新已有日报，避免点击“生成今日AI日报”无反应。
 * 3. 返回结果中携带 errorHint 字段，便于前端在 AI 失败时给出明确提示。
 */
@Service
public class DailyReportServiceImpl implements DailyReportService {

    private static final Logger log = LoggerFactory.getLogger(DailyReportServiceImpl.class);

    @Resource
    private DailyReportMapper dailyReportMapper;
    @Resource
    private HealthRecordService healthRecordService;
    @Resource
    private FraudAlertService fraudAlertService;
    @Resource
    private CareOrderService careOrderService;
    @Resource
    private CaregiverMapper caregiverMapper;
    @Resource
    private LlmUtil llmUtil;
    @Resource
    private PromptUtil promptUtil;

    @Override
    public DailyReport generateDailyReport(LocalDate date) {
        if (date == null) {
            date = LocalDate.now();
        }

        int healthCount = 0;
        int highRiskCount = 0;
        int fraudCount = 0;
        int orderCount = 0;

        try {
            LocalDateTime start = date.atStartOfDay();
            LocalDateTime end = date.atTime(LocalTime.MAX);
            healthCount = safeCount(() -> healthRecordService.countByTimeRange(start, end));
            highRiskCount = safeCount(() -> healthRecordService.countHighRisk(start, end));
            fraudCount = safeCount(() -> fraudAlertService.countByTimeRange(start, end));
            orderCount = safeCount(() -> careOrderService.countByTimeRange(start, end));
        } catch (Exception e) {
            log.warn("日报统计数据采集异常: {}", e.getMessage());
        }

        // 接单最多的护工
        String topCaregiverName = "";
        try {
            List<Caregiver> top = caregiverMapper.selectTopByTotal(1);
            if (top != null && !top.isEmpty() && top.get(0) != null) {
                topCaregiverName = top.get(0).getName();
            }
        } catch (Exception e) {
            log.warn("护工排行查询失败: {}", e.getMessage());
        }

        // AI 生成摘要（失败时走兜底，不抛异常）
        String summary;
        String errorHint = null;
        try {
            Map<String, String> params = new HashMap<>();
            params.put("data", String.format(
                    "健康记录%d条，高危老人%d位，反诈拦截%d条，工单%d个，今日接单最多的护工是%s",
                    healthCount, highRiskCount, fraudCount, orderCount,
                    topCaregiverName.isBlank() ? "暂无" : topCaregiverName
            ));
            String prompt = promptUtil.getPrompt("order_report.txt", params);
            String aiResult = llmUtil.chatSync(prompt);
            if (aiResult == null || aiResult.isBlank()
                    || aiResult.contains("AI服务暂未配置") || aiResult.contains("未配置任何大模型")) {
                throw new IllegalStateException("AI 未正确配置或返回空");
            }
            summary = aiResult.trim();
        } catch (Exception e) {
            log.warn("AI日报生成失败: {}，使用兜底摘要", e.getMessage());
            summary = String.format("今日健康记录%d条，高危%d人，反诈拦截%d条，工单%d个。%s",
                    healthCount, highRiskCount, fraudCount, orderCount,
                    topCaregiverName.isBlank() ? "" : "今日接单最多的护工是" + topCaregiverName + "。");
            errorHint = "AI摘要未生成（请检查 ai.aliyun.dashscope-api-key 是否配置正确）";
        }

        DailyReport report = new DailyReport();
        report.setReportDate(date);
        report.setHealthRecordCount(healthCount);
        report.setHighRiskCount(highRiskCount);
        report.setFraudBlockCount(fraudCount);
        report.setOrderCount(orderCount);
        report.setSummary(summary);

        // 持久化：同一天存在则更新，不存在则插入，任何数据库异常都不影响返回兜底结果
        try {
            DailyReport existing = dailyReportMapper.selectByDate(date);
            if (existing == null) {
                dailyReportMapper.insert(report);
            } else {
                report.setId(existing.getId());
                dailyReportMapper.updateById(report);
            }
            log.info("日报已生成/更新, date={}, summary={}", date, summary);
        } catch (Exception e) {
            log.error("日报持久化失败: {}", e.getMessage(), e);
            errorHint = (errorHint == null ? "" : errorHint + "；") + "日报保存失败：" + e.getMessage();
        }

        // 把提示带出去，前端可据此显示“AI未配置”等具体原因
        if (errorHint != null) {
            report.setSummary(summary + "\n[" + errorHint + "]");
        }
        return report;
    }

    @Override
    public DailyReport getByDate(LocalDate date) {
        try {
            return dailyReportMapper.selectByDate(date);
        } catch (Exception e) {
            log.warn("查询指定日期日报失败: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public List<DailyReport> listRecent(int days) {
        try {
            return dailyReportMapper.selectRecent(days);
        } catch (Exception e) {
            log.warn("查询最近日报失败: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public int clearAll() {
        try {
            return dailyReportMapper.deleteAll();
        } catch (Exception e) {
            log.warn("清空日报失败: {}", e.getMessage());
            throw new RuntimeException("清空日报失败：" + e.getMessage(), e);
        }
    }

    @Override
    public java.util.Map<String, Object> buildDashboard() {
        LocalDate today = LocalDate.now();
        LocalDateTime start = today.atStartOfDay();
        LocalDateTime end = today.atTime(LocalTime.MAX);

        java.util.Map<String, Object> data = new HashMap<>();
        data.put("date", today.toString());
        data.put("healthCount", safeCount(() -> healthRecordService.countByTimeRange(start, end)));
        data.put("highRiskCount", safeCount(() -> healthRecordService.countHighRisk(start, end)));
        data.put("fraudCount", safeCount(() -> fraudAlertService.countByTimeRange(start, end)));
        data.put("orderCount", safeCount(() -> careOrderService.countByTimeRange(start, end)));
        data.put("topCaregivers", safeList(() -> caregiverMapper.selectTopByTotal(5)));
        data.put("recentReports", safeList(() -> dailyReportMapper.selectRecent(7)));
        return data;
    }

    private int safeCount(java.util.function.Supplier<Integer> supplier) {
        try {
            Integer v = supplier.get();
            return v == null ? 0 : v;
        } catch (Exception e) {
            log.warn("日报统计项失败: {}", e.getMessage());
            return 0;
        }
    }

    private <T> List<T> safeList(java.util.function.Supplier<List<T>> supplier) {
        try {
            List<T> list = supplier.get();
            return list == null ? List.of() : list;
        } catch (Exception e) {
            log.warn("日报列表查询失败: {}", e.getMessage());
            return List.of();
        }
    }
}

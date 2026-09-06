package com.elderly.controller;

import com.elderly.common.R;
import com.elderly.entity.DailyReport;
import com.elderly.service.DailyReportService;
import jakarta.annotation.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;

/**
 * 日报接口 —— 查询/生成社区养老日报
 */
@RestController
@RequestMapping("/api/report")
public class DailyReportController {

    @Resource
    private DailyReportService dailyReportService;

    /**
     * 查询指定日期日报
     */
    @GetMapping("/{date}")
    public R<DailyReport> getByDate(@PathVariable @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date) {
        DailyReport report = dailyReportService.getByDate(date);
        if (report == null) {
            return R.fail(404, "该日期暂无日报");
        }
        return R.success(report);
    }

    /**
     * 查询最近N天日报
     */
    @GetMapping("/recent")
    public R<List<DailyReport>> recent(@RequestParam(defaultValue = "7") int days) {
        return R.success(dailyReportService.listRecent(days));
    }

    /**
     * 手动生成今日日报
     */
    @PostMapping("/generate")
    public R<DailyReport> generate(@RequestParam(required = false)
                                   @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date) {
        if (date == null) {
            date = LocalDate.now();
        }
        DailyReport report = dailyReportService.generateDailyReport(date);
        return R.success("日报生成成功", report);
    }

    /**
     * 数据看板（小程序管理端首页）：今日统计 + 最近日报 + 护工排行
     */
    @GetMapping("/dashboard")
    public R<java.util.Map<String, Object>> dashboard() {
        return R.success(dailyReportService.buildDashboard());
    }

    /**
     * 清空所有日报
     */
    @DeleteMapping("/clear")
    public R<String> clear() {
        try {
            int rows = dailyReportService.clearAll();
            return R.success("已清空 " + rows + " 条日报记录");
        } catch (Exception e) {
            return R.fail(500, "清空日报失败：" + e.getMessage());
        }
    }
}

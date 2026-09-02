package com.elderly.service;

import com.elderly.entity.DailyReport;
import java.time.LocalDate;
import java.util.List;

public interface DailyReportService {

    /** 生成指定日期的日报 */
    DailyReport generateDailyReport(LocalDate date);

    /** 查询指定日期日报 */
    DailyReport getByDate(LocalDate date);

    /** 查询最近N天日报 */
    List<DailyReport> listRecent(int days);

    /** 数据看板：今日统计 + 最近日报 + 护工排行 */
    java.util.Map<String, Object> buildDashboard();
}

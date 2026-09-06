package com.elderly.mapper;

import com.elderly.entity.DailyReport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDate;
import java.util.List;

@Mapper
public interface DailyReportMapper {

    /** 新增日报 */
    int insert(DailyReport report);

    /** 根据日期查询日报 */
    DailyReport selectByDate(@Param("reportDate") LocalDate reportDate);

    /** 更新日报 */
    int updateById(DailyReport report);

    /** 查询最近N天日报 */
    List<DailyReport> selectRecent(@Param("days") int days);

    /** 清空所有日报 */
    int deleteAll();
}

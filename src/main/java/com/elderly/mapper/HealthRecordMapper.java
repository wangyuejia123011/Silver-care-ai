package com.elderly.mapper;

import com.elderly.entity.HealthRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface HealthRecordMapper {

    /** 新增健康记录 */
    int insert(HealthRecord record);

    /** 根据ID查询 */
    HealthRecord selectById(@Param("id") Long id);

    /** 根据用户ID查询健康记录列表 */
    List<HealthRecord> selectByUserId(@Param("userId") Long userId);

    /** 查询某用户最近的健康记录 */
    HealthRecord selectLatestByUserId(@Param("userId") Long userId);

    /** 查询某用户最近 N 条健康记录（用于"今日守护"趋势分析） */
    List<HealthRecord> selectRecentByUserId(@Param("userId") Long userId, @Param("limit") int limit);

    /** 查询某用户当日健康记录（用于"今日守护"聚合） */
    List<HealthRecord> selectTodayByUserId(@Param("userId") Long userId,
                                           @Param("dayStart") LocalDateTime dayStart,
                                           @Param("dayEnd") LocalDateTime dayEnd);

    /** 统计某时间段内健康记录数 */
    int countByTimeRange(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /** 统计某时间段内高危记录数 */
    int countHighRiskByTimeRange(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /** 查询某时间段内的所有记录 */
    List<HealthRecord> selectByTimeRange(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /** 根据用户ID清空健康记录 */
    int deleteByUserId(@Param("userId") Long userId);
}

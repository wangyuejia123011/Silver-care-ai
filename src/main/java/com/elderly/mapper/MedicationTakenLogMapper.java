package com.elderly.mapper;

import com.elderly.entity.MedicationTakenLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Mapper
public interface MedicationTakenLogMapper {

    /** 新增打卡记录 */
    int insert(MedicationTakenLog log);

    /**
     * 删除某条打卡记录（老人取消勾选时调用）。
     * 返回 0 表示本来就没打过卡（视为幂等成功）。
     */
    int deleteByKey(@Param("userId") Long userId,
                    @Param("doseTime") LocalTime doseTime,
                    @Param("drugName") String drugName,
                    @Param("takenDate") LocalDate takenDate);

    /** 判断某条药当天是否已打卡 */
    int countByKey(@Param("userId") Long userId,
                   @Param("doseTime") LocalTime doseTime,
                   @Param("drugName") String drugName,
                   @Param("takenDate") LocalDate takenDate);

    /** 查某老人某天的全部打卡记录 */
    List<MedicationTakenLog> selectByUserIdAndDate(@Param("userId") Long userId,
                                                    @Param("takenDate") LocalDate takenDate);
}

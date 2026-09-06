package com.elderly.mapper;

import com.elderly.entity.FraudAlert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface FraudAlertMapper {

    /** 新增反诈记录 */
    int insert(FraudAlert alert);

    /** 根据用户ID查询反诈记录 */
    List<FraudAlert> selectByUserId(@Param("userId") Long userId);

    /** 统计某时间段内反诈拦截数 */
    int countByTimeRange(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /** 查询未处理记录 */
    List<FraudAlert> selectUnhandled();

    /** 根据用户ID清空反诈记录 */
    int deleteByUserId(@Param("userId") Long userId);
}

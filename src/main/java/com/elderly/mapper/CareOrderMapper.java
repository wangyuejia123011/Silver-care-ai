package com.elderly.mapper;

import com.elderly.entity.CareOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CareOrderMapper {

    /** 新增工单 */
    int insert(CareOrder order);

    /** 根据ID查询 */
    CareOrder selectById(@Param("id") Long id);

    /** 根据用户ID查询工单列表 */
    List<CareOrder> selectByUserId(@Param("userId") Long userId);

    /** 更新工单状态 */
    int updateStatus(@Param("id") Long id, @Param("status") String status, @Param("handlerName") String handlerName);

    /** 统计某时间段内工单数 */
    int countByTimeRange(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /** 查询待处理工单 */
    List<CareOrder> selectPending();

    /** 查询某护工名下的工单 */
    List<CareOrder> selectByCaregiverId(@Param("caregiverId") Long caregiverId);

    /** 指派工单给护工（状态置为assigned） */
    int assignCaregiver(@Param("id") Long id, @Param("caregiverId") Long caregiverId,
                        @Param("handlerName") String handlerName);
}

package com.elderly.service;

import com.elderly.entity.CareOrder;
import java.time.LocalDateTime;
import java.util.List;

public interface CareOrderService {

    /** 创建工单（AI生成内容） */
    CareOrder createOrder(CareOrder order);

    /** 根据ID查询 */
    CareOrder getById(Long id);

    /** 查询用户工单列表 */
    List<CareOrder> listByUserId(Long userId);

    /** 更新工单状态 */
    boolean updateStatus(Long id, String status, String handlerName);

    /** 查询待处理工单 */
    List<CareOrder> listPending();

    /** 查询某护工名下工单 */
    List<CareOrder> listByCaregiverId(Long caregiverId);

    /** 统计某时间段内工单数 */
    int countByTimeRange(LocalDateTime start, LocalDateTime end);

    /** 指派护工（状态置为assigned，护工负载+1） */
    boolean assignCaregiver(Long orderId, Long caregiverId);

    /** 清空用户工单 */
    int clearByUserId(Long userId);
}

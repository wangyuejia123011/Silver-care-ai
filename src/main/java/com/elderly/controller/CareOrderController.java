package com.elderly.controller;

import com.elderly.agent.OrderDispatchAgent;
import com.elderly.common.R;
import com.elderly.dto.OrderRequest;
import com.elderly.entity.CareOrder;
import com.elderly.entity.Caregiver;
import com.elderly.service.CareOrderService;
import com.elderly.service.CaregiverService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * 护工工单接口 —— AI生成工单、查询、状态管理
 */
@Slf4j
@RestController
@RequestMapping("/api/order")
public class CareOrderController {

    @Resource
    private CareOrderService careOrderService;
    @Resource
    private CaregiverService caregiverService;
    @Resource
    private OrderDispatchAgent orderDispatchAgent;

    /**
     * 创建工单（AI自动生成工单内容）
     */
    @PostMapping("/create")
    public R<CareOrder> create(@Valid @RequestBody OrderRequest req) {
        if (req.getUserId() == null) {
            return R.fail(400, "用户ID不能为空");
        }
        CareOrder order = new CareOrder();
        order.setUserId(req.getUserId());
        order.setElderlyName(req.getElderlyName());
        order.setAddress(req.getAddress());
        order.setPhone(req.getPhone());
        order.setOrderType(req.getOrderType() != null ? req.getOrderType() : "daily");
        order.setDemand(req.getDemand());

        CareOrder created = careOrderService.createOrder(order);
        return R.success("工单创建成功", created);
    }

    /**
     * 查询用户工单列表
     */
    @GetMapping("/list/{userId}")
    public R<List<CareOrder>> list(@PathVariable Long userId) {
        return R.success(careOrderService.listByUserId(userId));
    }

    /**
     * 查询工单详情
     */
    @GetMapping("/{id}")
    public R<CareOrder> getById(@PathVariable Long id) {
        CareOrder order = careOrderService.getById(id);
        if (order == null) {
            return R.fail(404, "工单不存在");
        }
        return R.success(order);
    }

    /**
     * 更新工单状态
     */
    @PutMapping("/status")
    public R<Void> updateStatus(@RequestParam Long id,
                                @RequestParam String status,
                                @RequestParam(required = false) String handlerName) {
        careOrderService.updateStatus(id, status, handlerName);
        return R.success("状态更新成功");
    }

    /**
     * 查询待处理工单
     */
    @GetMapping("/pending")
    public R<List<CareOrder>> pending() {
        return R.success(careOrderService.listPending());
    }

    /**
     * 智能派单（Agent5加权调度）：技能过滤→区域过滤→负载均衡
     */
    @PostMapping("/smart-dispatch")
    public R<CareOrder> smartDispatch(@Valid @RequestBody OrderRequest req) {
        if (req.getUserId() == null || req.getDemand() == null || req.getDemand().isBlank()) {
            return R.fail(400, "用户ID和需求描述不能为空");
        }
        CareOrder order = new CareOrder();
        order.setUserId(req.getUserId());
        order.setElderlyName(req.getElderlyName());
        order.setAddress(req.getAddress());
        order.setPhone(req.getPhone());
        order.setOrderType(req.getOrderType() != null ? req.getOrderType() : "daily");
        order.setDemand(req.getDemand());

        CareOrder created = careOrderService.createOrder(order);

        // 加权匹配最佳护工并指派（内部含WebSocket实时推送）
        Caregiver best = caregiverService.matchBestCaregiver(
                orderDispatchAgent.extractSkill(req.getDemand()), order.getAddress());
        if (best != null) {
            careOrderService.assignCaregiver(created.getId(), best.getId());
            created.setCaregiverId(best.getId());
            created.setHandlerName(best.getName());
            created.setStatus("assigned");
        }
        return R.success("智能派单完成", created);
    }

    /**
     * 清空用户工单
     */
    @DeleteMapping("/clear/{userId}")
    public R<Void> clearByUserId(@PathVariable Long userId) {
        try {
            careOrderService.clearByUserId(userId);
            return R.success("已清空");
        } catch (Exception e) {
            log.error("清空工单异常, userId={}: {}", userId, e.getMessage(), e);
            return R.fail(500, "清空失败：" + e.getMessage());
        }
    }
}

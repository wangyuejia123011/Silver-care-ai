package com.elderly.service.impl;

import com.elderly.mapper.CareOrderMapper;
import com.elderly.mapper.CaregiverMapper;
import com.elderly.service.CareOrderService;
import com.elderly.util.LlmUtil;
import com.elderly.util.PromptUtil;
import com.elderly.ws.OrderWebSocketHandler;
import com.elderly.entity.CareOrder;
import com.elderly.entity.Caregiver;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class CareOrderServiceImpl implements CareOrderService {

    private static final Logger log = LoggerFactory.getLogger(CareOrderServiceImpl.class);

    @Resource
    private CareOrderMapper careOrderMapper;
    @Resource
    private CaregiverMapper caregiverMapper;
    @Resource
    private LlmUtil llmUtil;
    @Resource
    private PromptUtil promptUtil;
    @Resource
    private OrderWebSocketHandler wsHandler;

    @Override
    public CareOrder createOrder(CareOrder order) {
        // 调用AI生成工单内容
        try {
            Map<String, String> params = new HashMap<>();
            params.put("demand", order.getDemand() != null ? order.getDemand() : "老人需要帮助");
            params.put("health", order.getHealthSummary() != null ? order.getHealthSummary() : "暂无健康数据");
            String prompt = promptUtil.getPrompt("order_generate/order_generate.txt", params);
            String content = llmUtil.chatSync(prompt);
            order.setOrderContent(content);

            // 简单判断是否需要医疗设备
            if (content != null && (content.contains("血压") || content.contains("血糖")
                    || content.contains("医疗") || content.contains("检查"))) {
                order.setNeedMedicalDevice(1);
            } else {
                order.setNeedMedicalDevice(0);
            }
        } catch (Exception e) {
            log.warn("AI工单生成失败，使用原始需求: {}", e.getMessage());
            order.setOrderContent(order.getDemand());
            order.setNeedMedicalDevice(0);
        }

        if (order.getStatus() == null) {
            order.setStatus("pending");
        }
        careOrderMapper.insert(order);
        log.info("工单已创建, userId={}, type={}", order.getUserId(), order.getOrderType());
        return order;
    }

    @Override
    public CareOrder getById(Long id) {
        return careOrderMapper.selectById(id);
    }

    @Override
    public List<CareOrder> listByUserId(Long userId) {
        return careOrderMapper.selectByUserId(userId);
    }

    @Override
    public boolean updateStatus(Long id, String status, String handlerName) {
        // 完成工单时释放护工负载
        if ("done".equals(status) || "cancelled".equals(status)) {
            CareOrder order = careOrderMapper.selectById(id);
            if (order != null && order.getCaregiverId() != null && "assigned".equals(order.getStatus())) {
                caregiverMapper.decreaseOrderCount(order.getCaregiverId());
            }
        }
        return careOrderMapper.updateStatus(id, status, handlerName) > 0;
    }

    @Override
    public List<CareOrder> listPending() {
        return careOrderMapper.selectPending();
    }

    @Override
    public List<CareOrder> listByCaregiverId(Long caregiverId) {
        return careOrderMapper.selectByCaregiverId(caregiverId);
    }

    @Override
    public int countByTimeRange(LocalDateTime start, LocalDateTime end) {
        return careOrderMapper.countByTimeRange(start, end);
    }

    @Override
    public boolean assignCaregiver(Long orderId, Long caregiverId) {
        Caregiver caregiver = caregiverMapper.selectById(caregiverId);
        if (caregiver == null) {
            return false;
        }
        int rows = careOrderMapper.assignCaregiver(orderId, caregiverId, caregiver.getName());
        if (rows > 0) {
            caregiverMapper.increaseOrderCount(caregiverId);
            // WebSocket实时推送给护工端
            CareOrder order = careOrderMapper.selectById(orderId);
            if (order != null) {
                wsHandler.pushOrder(caregiverId, order);
            }
        }
        return rows > 0;
    }

    @Override
    public int clearByUserId(Long userId) {
        return careOrderMapper.deleteByUserId(userId);
    }
}

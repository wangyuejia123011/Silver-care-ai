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
            order.setOrderContent(sanitizeOrderContent(content));

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

    /**
     * 清洗 AI 生成的工单文本：
     * 去掉 Markdown 符号（**、#、* 等），按字段标签和编号自动分行，
     * 保证小程序端、护工端、TTS 播报拿到的都是干净可读的纯文本。
     */
    private String sanitizeOrderContent(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        String s = raw.replace("**", "").replace("__", "").replace("`", "");
        // 标题符号（行首/行中）→ 换行
        s = s.replaceAll("#{1,6}", "\n");
        // 行首列表符号
        s = s.replaceAll("(?m)^\\s*[-*•]\\s+", "");
        // 残留的单独星号
        s = s.replace("*", "");
        // 常见字段标签前换行
        s = s.replaceAll("\\s*(服务对象|当前状况|风险等级|是否携带设备|服务内容|行动建议|健康数据状态|健康数据|原因说明|温馨提示|温馨小贴士|小贴士|给您的小建议|小建议|护理建议|注意事项|回答)\\s*[:：]", "\n$1：");
        // "是否需要携带医疗设备"（后面可能跟 ? 或 ：）
        s = s.replaceAll("\\s*(是否需要携带医疗设备)\\s*([?？:：]?)", "\n$1$2");
        // 数字编号前换行
        s = s.replaceAll("\\s+(\\d{1,2})[.、]\\s*", "\n$1. ");
        // 折叠多余空格
        s = s.replaceAll("[ \\t]{2,}", " ");
        // 逐行去首尾空白、去空行
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\\n")) {
            String t = line.trim();
            if (!t.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(t);
            }
        }
        return sb.toString();
    }
}

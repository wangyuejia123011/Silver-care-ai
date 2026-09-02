package com.elderly.agent;

import com.elderly.entity.CareOrder;
import com.elderly.entity.Caregiver;
import com.elderly.entity.ElderlyUser;
import com.elderly.entity.HealthRecord;
import com.elderly.service.CareOrderService;
import com.elderly.service.CaregiverService;
import com.elderly.service.ElderlyUserService;
import com.elderly.service.HealthRecordService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * Agent5：社区工单调度智能体（加权调度算法）
 *
 * 链路：需求理解(AI提取技能标签) → 护工加权匹配（技能过滤→区域过滤→负载均衡）
 *       → AI生成工单(order_generate.txt) → 指派 → WebSocket实时推送护工端
 */
@Service
public class OrderDispatchAgent {

    private static final Logger log = LoggerFactory.getLogger(OrderDispatchAgent.class);

    @Resource
    private CareOrderService careOrderService;
    @Resource
    private CaregiverService caregiverService;
    @Resource
    private ElderlyUserService userService;
    @Resource
    private HealthRecordService healthRecordService;

    /** 技能标签别名映射：老人口语 → 标准技能 */
    private static final Map<String, String> SKILL_ALIASES = Map.ofEntries(
            Map.entry("急救", "急救"), Map.entry("紧急", "急救"), Map.entry("抢救", "急救"),
            Map.entry("康复", "康复"), Map.entry("按摩", "康复"), Map.entry("理疗", "康复"),
            Map.entry("陪诊", "陪诊"), Map.entry("看病", "陪诊"), Map.entry("医院", "陪诊"),
            Map.entry("助浴", "助浴"), Map.entry("洗澡", "助浴"),
            Map.entry("保洁", "保洁"), Map.entry("打扫", "保洁"), Map.entry("卫生", "保洁"),
            Map.entry("护理", "康复"), Map.entry("血压", "急救"), Map.entry("头晕", "急救")
    );

    /**
     * 老人语音/文字服务请求 → 自动生成工单并智能派单。
     *
     * @param demand 老人原始需求文本
     * @param userId 老人用户ID（用于补全姓名/地址/电话/健康摘要）
     * @param emergency 是否紧急工单（健康高危预警触发）
     */
    public AgentResult dispatch(String demand, Long userId, boolean emergency) {
        CareOrder order = new CareOrder();
        order.setUserId(userId);
        order.setDemand(demand);
        order.setOrderType(emergency ? "emergency" : "daily");

        // 1. 补全老人档案信息
        ElderlyUser user = userId != null ? userService.getById(userId) : null;
        if (user != null) {
            order.setElderlyName(user.getName());
            order.setAddress(user.getAddress());
            order.setPhone(user.getPhone());
        }
        if (order.getAddress() == null) order.setAddress("地址待补充");

        // 2. 带上最近健康数据生成更精准的工单（文档：标注是否需带设备）
        try {
            if (userId != null) {
                HealthRecord latest = healthRecordService.getLatest(userId);
                if (latest != null) {
                    String summary = String.format("血压%d/%d，心率%s，血糖%s",
                            latest.getSystolicPressure() == null ? 0 : latest.getSystolicPressure(),
                            latest.getDiastolicPressure() == null ? 0 : latest.getDiastolicPressure(),
                            latest.getHeartRate() == null ? "未知" : latest.getHeartRate(),
                            latest.getBloodSugar() == null ? "未知" : latest.getBloodSugar());
                    order.setHealthSummary(summary);
                }
            }
        } catch (Exception e) {
            log.warn("查询健康摘要失败: {}", e.getMessage());
        }

        // 3. AI生成工单内容（order_generate.txt）
        order = careOrderService.createOrder(order);

        // 4. 技能提取（本地别名匹配，无需调用大模型，保证派单速度）
        String skill = extractSkill(demand);
        if (emergency && skill == null) {
            skill = "急救";
        }

        // 5. 加权匹配：技能过滤 → 区域过滤 → 负载均衡
        Caregiver best = caregiverService.matchBestCaregiver(skill, order.getAddress());
        if (best != null) {
            careOrderService.assignCaregiver(order.getId(), best.getId());
            order.setCaregiverId(best.getId());
            order.setHandlerName(best.getName());
            order.setStatus("assigned");
            log.info("工单#{} 已指派给 {}（技能={}, 区域={}, 当前负载={}）",
                    order.getId(), best.getName(), skill, best.getArea(), best.getCurrentOrderCount());
        }

        // 6. 组装给老人的口语化回复
        String reply;
        if (best != null) {
            reply = String.format("好的，已经帮您安排好了。护工%s正在赶来的路上，请您先原地休息，预计15分钟内到达。%s",
                    best.getName(),
                    order.getNeedMedicalDevice() != null && order.getNeedMedicalDevice() == 1
                            ? "护工会携带血压计等设备上门。" : "");
        } else {
            reply = "好的，您的需求已经登记，社区会尽快安排护工与您联系，请保持电话畅通。";
        }

        AgentResult result = AgentResult.of("order", Flux.just(reply));
        result.setOrder(order);
        return result;
    }

    /** 从老人需求文本中提取标准技能标签（统一入口，Controller和Agent内部共用） */
    public String extractSkill(String demand) {
        if (demand == null) return null;
        for (Map.Entry<String, String> e : SKILL_ALIASES.entrySet()) {
            if (demand.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    /** 查询护工排行（数据看板用） */
    public List<Caregiver> topCaregivers(int limit) {
        return caregiverService.listTopByTotal(limit);
    }
}

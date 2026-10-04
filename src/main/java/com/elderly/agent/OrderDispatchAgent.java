package com.elderly.agent;

import com.alibaba.fastjson2.JSONObject;
import com.elderly.entity.CareOrder;
import com.elderly.entity.Caregiver;
import com.elderly.entity.DispatchContext;
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
    /** AI 性别判定用（护工该派男还是女，由大模型按需求语义判断） */
    @Resource
    private com.elderly.util.LlmUtil llmUtil;
    @Resource
    private com.elderly.util.PromptUtil promptUtil;

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

        // 3. 场景化分类：根据需求文本 + 老人性别/健康情况，判定所需技能、是否带设备、护工性别要求
        DispatchContext ctx = classify(demand, order.getHealthSummary(), emergency,
                user != null ? user.getGender() : null);
        order.setOrderType(categoryToOrderType(ctx.getCategory()));
        order.setNeedMedicalDevice(ctx.isNeedDevice() ? 1 : 0);

        // 4. AI生成工单内容（order_generate.txt）
        order = careOrderService.createOrder(order);

        // 5. 场景化加权匹配：技能 → 性别(助浴) → 医疗设备(健康类) → 区域/距离 → 负载均衡
        Caregiver best = caregiverService.matchBestCaregiver(ctx, order.getAddress());
        if (best != null) {
            careOrderService.assignCaregiver(order.getId(), best.getId());
            order.setCaregiverId(best.getId());
            order.setHandlerName(best.getName());
            order.setStatus("assigned");
            log.info("工单#{} 已指派给 {}（场景={}, 技能={}, 性别={}, 带设备={}, 区域={}, 当前负载={}）",
                    order.getId(), best.getName(), ctx.getCategory(), ctx.getSkill(), best.getGender(),
                    best.getCanCarryDevice(), best.getArea(), best.getCurrentOrderCount());
        }

        // 6. 组装给老人的口语化回复（依据场景给出差异化的说明）
        String reply;
        if (best != null) {
            StringBuilder sb = new StringBuilder("好的，已经帮您安排好了。护工");
            sb.append(best.getName()).append("正在赶来的路上，请您先休息，预计15分钟内到达。");
            if (ctx.isNeedDevice()) {
                sb.append("这是健康相关服务，护工会携带血压计等医疗设备上门。");
            }
            if (ctx.getRequireGender() != null && best.getGender() != null
                    && ctx.getRequireGender().equals(best.getGender())) {
                // 按强度给老人不同的说法：隐私类说明"同性别更方便"，体力类说明"安排了男护工"
                if ("must".equals(ctx.getGenderStrength())) {
                    sb.append("考虑到这次照料涉及隐私，已为您安排同性别的护工。");
                } else {
                    sb.append("这次是体力活，已为您安排").append(ctx.getRequireGender())
                            .append("性护工。");
                }
            }
            if ("DAILY".equals(ctx.getCategory())) {
                sb.append("这是日常照料，无需携带医疗设备。");
            }
            reply = sb.toString();
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

    /** 场景分类关键字集合 */
    private static final java.util.Set<String> EMERGENCY_KW = java.util.Set.of(
            "急救", "120", "昏迷", "晕倒", "晕过去", "中风", "心梗", "高危", "危急", "摔倒", "跌到", "抽搐", "叫救护车");
    private static final java.util.Set<String> BATH_KW = java.util.Set.of(
            "助浴", "洗澡", "洗浴", "沐浴", "擦浴", "洗个澡");
    private static final java.util.Set<String> HEALTH_KW = java.util.Set.of(
            "血压", "血糖", "心率", "体温", "测量", "检测", "监测", "医疗", "护理", "陪诊", "康复",
            "吃药", "用药", "输液", "换药", "复查", "体检",
            "头晕", "头疼", "头痛", "发烧", "发热", "心慌", "胸闷", "不舒服", "难受", "乏力", "恶心");
    private static final java.util.Set<String> DEVICE_KW = java.util.Set.of(
            "血压", "血糖", "心率", "体温", "测量", "检测", "监测", "医疗", "急救",
            "吃药", "用药", "输液", "换药",
            "头晕", "头疼", "头痛", "发烧", "发热", "心慌", "胸闷");
    private static final java.util.Set<String> DAILY_KW = java.util.Set.of(
            "打扫", "保洁", "卫生", "换灯泡", "灯泡", "修理", "维修", "买菜", "做饭",
            "取药", "散步", "遛弯", "陪伴", "聊天", "洗衣", "倒垃圾");

    /**
     * 把老人需求翻译成可调度约束（场景分类）。
     * - 紧急/健康：需携带医疗设备（血压计等）上门；
     * - 日常照料：无需设备。
     * - 护工性别：不由场景硬编码，而是交给 AI 按需求内容判断
     *   （隐私类如助浴→同性别 must；体力类如换灯泡→男性 prefer；其余→不限）。
     */
    public DispatchContext classify(String demand, String healthSummary, boolean emergency, String elderlyGender) {
        DispatchContext ctx = new DispatchContext();
        ctx.setEmergency(emergency);
        ctx.setSkill(extractSkill(demand));
        String d = demand == null ? "" : demand;

        if (emergency || containsAny(d, EMERGENCY_KW)) {
            ctx.setCategory("EMERGENCY");
            if (ctx.getSkill() == null) ctx.setSkill("急救");
            ctx.setNeedDevice(true);
        } else if (containsAny(d, BATH_KW)) {
            ctx.setCategory("BATH");
            ctx.setNeedDevice(false);
            if (ctx.getSkill() == null) ctx.setSkill("助浴");
        } else if (containsAny(d, HEALTH_KW)) {
            ctx.setCategory("HEALTH");
            if (ctx.getSkill() == null) ctx.setSkill("康复");
            boolean deviceByDemand = containsAny(d, DEVICE_KW) || "急救".equals(ctx.getSkill());
            boolean deviceByHealth = healthSummary != null && healthSummary.contains("高危");
            ctx.setNeedDevice(deviceByDemand || deviceByHealth);
        } else {
            ctx.setCategory("DAILY");
            ctx.setNeedDevice(false);
            if (ctx.getSkill() == null) ctx.setSkill("保洁");
        }

        // 护工性别：AI 按需求语义判断，失败降级为关键词规则
        applyGenderRequirement(ctx, demand, elderlyGender);
        return ctx;
    }

    /**
     * 用大模型判断该需求应该派男护工、女护工还是不限。
     *
     * 设计要点：
     * - 关键词只做兜底，主判定走 AI —— 因为"换灯泡要男的""助浴要同性别"
     *   这类判断需要理解整句话，光靠关键词既会漏也会误判；
     * - AI 不可用（未配置 key / 超时 / 返回非 JSON）时降级到关键词规则，
     *   宁可粗判也不能让老人叫不到人；
     * - 强度分 must（隐私类硬约束）/ prefer（体力类偏好）/ any（不限），
     *   交由调度算法决定是"排除"还是"加分"。
     */
    private void applyGenderRequirement(DispatchContext ctx, String demand, String elderlyGender) {
        String text = demand == null ? "" : demand.trim();
        String gender = elderlyGender == null ? "" : elderlyGender.trim();

        try {
            Map<String, String> param = new java.util.HashMap<>();
            param.put("demand", text.isEmpty() ? "（老人未填写具体需求）" : text);
            param.put("genderText", gender.isEmpty() ? "未知" : gender);
            param.put("ageText", "未知");
            param.put("categoryText", categoryText(ctx.getCategory()));
            String prompt = promptUtil.getPrompt("dispatch_gender.txt", param);
            String aiOut = llmUtil.chatSync(prompt);
            JSONObject json = tryParseJson(aiOut == null ? "" : aiOut.trim());
            if (json != null) {
                String g = normalizeGender(json.getString("requireGender"));
                String strength = normalizeStrength(json.getString("strength"));
                // 隐私类需求（must）必须是老人同性别，AI 若判错方向这里兜底纠正
                if ("must".equals(strength) && g == null) {
                    g = gender.isEmpty() ? null : gender;
                }
                if (g != null) {
                    ctx.setRequireGender(g);
                    ctx.setGenderStrength(strength);
                    ctx.setGenderReason(json.getString("reason"));
                    log.info("AI 性别判定：需求=\"{}\" 老人={} → 要求{} ({}) 理由：{}",
                            abbreviate(text), gender.isEmpty() ? "未知" : gender, g, strength,
                            ctx.getGenderReason());
                    return;
                }
                // AI 明确判为不限
                ctx.setGenderStrength("any");
                ctx.setGenderReason(json.getString("reason"));
                log.info("AI 性别判定：需求=\"{}\" → 不限性别，理由：{}", abbreviate(text), ctx.getGenderReason());
                return;
            }
            log.warn("AI 性别判定返回非 JSON，降级关键词规则：{}", abbreviate(aiOut));
        } catch (Exception e) {
            log.warn("AI 性别判定异常，降级关键词规则: {}", e.getMessage());
        }

        // ===== 降级：关键词规则 =====
        GenderFallback fb = decideGenderByKeyword(text, gender);
        ctx.setRequireGender(fb.gender);
        ctx.setGenderStrength(fb.strength);
        ctx.setGenderReason(fb.reason);
        log.info("关键词降级判定：需求=\"{}\" → {} ({}) {}",
                abbreviate(text), fb.gender == null ? "不限" : fb.gender, fb.strength, fb.reason);
    }

    /** 关键词降级结果 */
    private static final class GenderFallback {
        final String gender;
        final String strength;
        final String reason;

        GenderFallback(String gender, String strength, String reason) {
            this.gender = gender;
            this.strength = strength;
            this.reason = reason;
        }
    }

    /** 隐私/身体接触关键词（与老人同性别） */
    /**
     * 隐私/身体接触关键词（与老人同性别）。
     * 收录「澡/浴/擦身/脱衣/剪指甲/导尿/纸尿裤/翻身」等**核心词元**而非固定短语 ——
     * 老人说的是口语（「洗个澡」「帮我擦擦身」），只收「洗澡」「洗浴」这类书面语会漏判。
     */
    private static final java.util.Set<String> INTIMACY_KW = java.util.Set.of(
            "助浴", "澡", "浴", "擦身", "擦屁股", "淋浴",
            "换衣", "脱衣", "穿衣", "裤", "剪指甲", "剪脚指甲", "抠脚", "剪指甲刀",
            "导尿", "尿管", "插尿", "纸尿裤", "尿不湿", "尿垫", "翻身", "翻个身",
            "私处", "隐私", "裸", "暴露", "扶我上厕所", "上厕所扶", "擦药膏", "涂药");
    /**
     * 体力/技术作业关键词（男性优先）。
     * 同样按词元收录：「灯泡」能命中「换灯泡」「灯泡坏了」；「水管」能命中「修水管」；
     * 「轮椅」能命中「推轮椅」「轮椅推到楼下」。
     */
    private static final java.util.Set<String> MANUAL_KW = java.util.Set.of(
            "灯泡", "灯管", "灯座", "开关", "插座", "电线", "水电", "水管", "龙头", "马桶",
            "修理", "维修", "修一下", "修好", "坏了", "拧", "螺丝", "搬", "抬", "扛", "拽",
            "推轮椅", "轮椅", "爬梯", "梯子", "登高", "高处", "擦玻璃", "擦窗户",
            "组装", "安装", "高空", "力气", "费劲", "搬不动", "抬不动");

    /**
     * 关键词降级判定。规则与 prompts/dispatch_gender.txt 保持一致：
     * 隐私 > 体力 > 不限。
     */
    private GenderFallback decideGenderByKeyword(String text, String gender) {
        if (containsAny(text, INTIMACY_KW)) {
            if (gender.isEmpty()) {
                return new GenderFallback(null, "any", "涉隐私需求但老人性别未知，不限性别");
            }
            return new GenderFallback(gender, "must", "隐私/身体接触类需求，需同性别护工");
        }
        if (containsAny(text, MANUAL_KW)) {
            return new GenderFallback("男", "prefer", "体力/登高作业类需求，男性优先");
        }
        return new GenderFallback(null, "any", "与性别无关");
    }

    /** 场景分类 → 中文说明，供 prompt 使用 */
    private String categoryText(String category) {
        return switch (category == null ? "" : category) {
            case "EMERGENCY" -> "紧急求助";
            case "BATH" -> "助浴（身体接触照护）";
            case "HEALTH" -> "健康服务";
            case "DAILY" -> "日常照料";
            default -> "日常照料";
        };
    }

    /** 归一化性别值：只接受"男"/"女"，其余（含"男性""male"）一律按无效处理 */
    private String normalizeGender(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty() || "null".equalsIgnoreCase(v) || "不限".equals(v) || "任意".equals(v)) {
            return null;
        }
        if (v.startsWith("男")) return "男";
        if (v.startsWith("女")) return "女";
        return null;
    }

    /** 归一化强度值 */
    private String normalizeStrength(String raw) {
        if (raw == null) return "any";
        String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (v) {
            case "must", "force", "required" -> "must";
            case "prefer", "preferred" -> "prefer";
            default -> "any";
        };
    }

    private JSONObject tryParseJson(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            String clean = text.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            return com.alibaba.fastjson2.JSON.parseObject(clean);
        } catch (Exception e) {
            return null;
        }
    }

    private String abbreviate(String s) {
        if (s == null) return "";
        return s.length() > 30 ? s.substring(0, 30) + "…" : s;
    }

    private boolean containsAny(String text, java.util.Set<String> keywords) {
        if (text == null) return false;
        for (String k : keywords) {
            if (text.contains(k)) return true;
        }
        return false;
    }

    /**
     * 工单类别只分两大类：日常照料(daily) / 健康服务(health)。
     * 紧急求助属健康服务的极端情况，归入 health；助浴属日常照料。
     */
    private String categoryToOrderType(String category) {
        return switch (category) {
            case "EMERGENCY", "HEALTH" -> "health";
            default -> "daily";
        };
    }

    /** 查询护工排行（数据看板用） */
    public List<Caregiver> topCaregivers(int limit) {
        return caregiverService.listTopByTotal(limit);
    }
}

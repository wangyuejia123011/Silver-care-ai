package com.elderly.agent;

import com.alibaba.fastjson2.JSON;
import com.elderly.entity.CareOrder;
import com.elderly.entity.ElderlyUser;
import com.elderly.entity.HealthRecord;
import com.elderly.service.ElderlyUserService;
import com.elderly.service.HealthNotifyService;
import com.elderly.service.HealthRecordService;
import com.elderly.util.ChromaUtil;
import com.elderly.util.CnNumberUtil;
import com.elderly.util.HealthRiskEvaluator;
import com.elderly.util.LlmUtil;
import com.elderly.util.PromptUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent2：慢病健康分析智能体
 *
 * 完整链路（与用户要求的流程对应）：
 *   老人语音
 *      ↓
 *   方言 / 语音识别（VoiceAgent 中完成，本 Agent 接收文本）
 *      ↓
 *   大模型理解意图 + 正则提取指标
 *      ↓
 *   读取老人档案（年龄、基础资料、既往病史）
 *      ↓
 *   读取当天健康数据（当日已有健康记录）
 *      ↓
 *   RAG 检索可信健康知识
 *      ↓
 *   AI 风险分级（high / medium / low）
 *      ↓
 *   生成适老化回答
 *      ↓
 *   必要时提醒家属（中/高风险时 HealthNotifyService 通知）
 *      ↓
 *   形成健康事件记录（落库 health_record）
 */
@Service
public class HealthAgent {

    private static final Logger log = LoggerFactory.getLogger(HealthAgent.class);

    @Resource
    private ChromaUtil chromaUtil;
    @Resource
    private LlmUtil llmUtil;
    @Resource
    private PromptUtil promptUtil;
    @Resource
    private HealthRecordService healthRecordService;
    @Resource
    private ElderlyUserService userService;
    @Resource
    private OrderDispatchAgent orderDispatchAgent;
    @Resource
    private HealthNotifyService healthNotifyService;

    // 数字 token：阿拉伯数字 或 中文数字（含小数），例如 120 / 七十 / 十六点五 / 一百二
    private static final String NUM_TOKEN =
            "(\\d+(?:\\.\\d+)?|[零一二两三四五六七八九十百]+(?:[点\\.][0-9零一二两三四五六七八九十]+)?)";
    private static final Pattern PRESSURE_PATTERN =
            Pattern.compile("(\\d{2,3})\\s*[／/过到]\\s*(\\d{2,3})");
    // 兼容多种口语：血压/高压/低压，关键词与数字之间可带"是""为""值""："等间隔
    private static final Pattern SYS_ONLY_PATTERN =
            Pattern.compile("(?:血压|高压)[^0-9零一二两三四五六七八九十百]{0,5}" + NUM_TOKEN);
    private static final Pattern DIA_ONLY_PATTERN =
            Pattern.compile("(?:低压|舒张压)[^0-9零一二两三四五六七八九十百]{0,5}" + NUM_TOKEN);
    private static final Pattern SUGAR_PATTERN =
            Pattern.compile("(?:血糖|空腹血糖|餐后血糖|餐前血糖)[^0-9零一二两三四五六七八九十百]{0,5}" + NUM_TOKEN);
    private static final Pattern HEART_PATTERN =
            Pattern.compile("(?:心率|心跳|脉搏|心律)[^0-9零一二两三四五六七八九十百]{0,5}" + NUM_TOKEN);
    private static final Pattern TEMP_PATTERN =
            Pattern.compile("(?:体温|发烧|发热|温度)[^0-9零一二两三四五六七八九十百]{0,5}" + NUM_TOKEN);

    // 中文数字映射（覆盖老人口语常用）
    private static int cnDigit(char c) {
        switch (c) {
            case '零': return 0;
            case '一': return 1;
            case '二': case '两': return 2;
            case '三': return 3;
            case '四': return 4;
            case '五': return 5;
            case '六': return 6;
            case '七': return 7;
            case '八': return 8;
            case '九': return 9;
            default: return -1;
        }
    }

    public AgentResult analyseHealth(String userText, Long userId) {
        // ===== 0. 方言鲁棒化：关键词同音归一 + 是/十保险消歧 + 中文数字→阿拉伯（ASR 已做，此处补漏） =====
        String normText = normalizeDialectKeywords(userText);
        normText = ruleShiShiFix(normText);
        normText = CnNumberUtil.normalize(normText);

        // ===== 1. 大模型理解意图 + 正则提取指标 =====
        HealthIntent intent = extractIntentAndMetrics(normText);
        // 正则未抽全时，用大模型结构化抽取兜底（解决方言同音错字、中文双值血压等）
        intent = supplementByLlm(normText, intent);

        // ===== 2. 读取老人档案 =====
        ElderlyUser profile = null;
        if (userId != null) {
            try {
                profile = userService.getById(userId);
            } catch (Exception e) {
                log.warn("读取老人档案失败: {}", e.getMessage());
            }
        }

        // ===== 3. 读取当天健康数据 =====
        List<HealthRecord> todayRecords = null;
        if (userId != null) {
            try {
                todayRecords = healthRecordService.listTodayByUserId(userId);
            } catch (Exception e) {
                log.warn("读取当天健康数据失败: {}", e.getMessage());
            }
        }

        // 合并本次提取指标与当天历史指标，取最新值
        Integer systolic = intent.systolic;
        Integer diastolic = intent.diastolic;
        Integer heartRate = intent.heartRate;
        Double bloodSugar = intent.bloodSugar;
        Double temperature = intent.temperature;
        if (todayRecords != null && !todayRecords.isEmpty()) {
            HealthRecord latest = todayRecords.get(0);
            for (HealthRecord r : todayRecords) {
                if (r.getCreateTime() != null && latest.getCreateTime() != null
                        && r.getCreateTime().isAfter(latest.getCreateTime())) {
                    latest = r;
                }
            }
            if (systolic == null) systolic = latest.getSystolicPressure();
            if (diastolic == null) diastolic = latest.getDiastolicPressure();
            if (heartRate == null) heartRate = latest.getHeartRate();
            if (bloodSugar == null) bloodSugar = latest.getBloodSugar();
            if (temperature == null) temperature = latest.getTemperature();
        }

        boolean symptomHigh = hitSymptom(userText);
        String riskLevel = gradeRisk(systolic, diastolic, heartRate, bloodSugar, temperature, symptomHigh);
        boolean highRisk = "high".equals(riskLevel);

        // ===== 4. RAG 检索可信健康知识 =====
        String context = "";
        try {
            List<String> contextList = chromaUtil.searchMedicalDoc(userText);
            context = String.join("\n", contextList);
        } catch (Exception e) {
            log.warn("Chroma检索失败，降级直连大模型: {}", e.getMessage());
        }

        // ===== 5. 组装 Prompt：档案 + 当天数据 + 本次指标 + RAG =====
        String dataSummary = buildDataSummary(systolic, diastolic, heartRate, bloodSugar, temperature,
                riskLevel, todayRecords, profile);

        Map<String, String> params = new HashMap<>();
        params.put("context", context.isBlank() ? "（暂无参考资料，请按常识回答并提醒就医）" : context);
        params.put("data", dataSummary);
        params.put("question", normText);

        String fullPrompt;
        try {
            fullPrompt = promptUtil.getPrompt("health.txt", params);
        } catch (Exception e) {
            fullPrompt = "老人提问：" + userText + "（指标 " + dataSummary + "），请用50字以内通俗语气回答。";
        }

        // ===== 6. 高危：立即固定警告 + 通知家属 + 自动工单 =====
        if (highRisk) {
            String warning = HealthRiskEvaluator.buildHighRiskWarning(buildTempRecord(systolic, diastolic,
                    heartRate, bloodSugar, temperature, userText));
            warning += " 系统已通知家属和护工，马上会有人联系您！";

            HealthRecord record = buildRecord(userId, profile, systolic, diastolic, heartRate, bloodSugar,
                    temperature, "voice", normText, riskLevel);
            record.setIsAlert(1);
            record.setAiAdvice(warning);
            persistAndNotify(record, userText);

            AgentResult orderResult = orderDispatchAgent.dispatch(
                    "老人语音上报高危健康情况：" + dataSummary + "。原话：" + normText, userId, true);
            CareOrder order = orderResult.getOrder();

            AgentResult result = AgentResult.of("health", Flux.just(warning));
            result.setOrder(order);
            result.setRiskLevel(riskLevel);
            attachNotifySummary(result, record, userText);
            return result;
        }

        // ===== 7. 中/低危：先同步落库指标，再流式生成回答并补录AI建议 =====
        final Integer fSystolic = systolic, fDiastolic = diastolic, fHeartRate = heartRate;
        final Double fBloodSugar = bloodSugar, fTemperature = temperature;
        final String fRisk = riskLevel;
        final ElderlyUser fProfile = profile;

        AgentResult result = AgentResult.of("health", Flux.empty());
        result.setRiskLevel(riskLevel);

        // 先同步落库：指标立刻可见，避免SSE完成后前端刷新仍取不到数据
        Long recordId = null;
        try {
            HealthRecord record = buildRecord(userId, fProfile, fSystolic, fDiastolic, fHeartRate,
                    fBloodSugar, fTemperature, "voice", normText, fRisk);
            record.setAiAdvice(""); // 占位，流式完成后再更新
            persistAndNotify(record, userText);
            attachNotifySummary(result, record, userText);
            recordId = record.getId();
        } catch (Exception e) {
            log.warn("健康记录同步落库失败: {}", e.getMessage());
        }

        final Long finalRecordId = recordId;
        StringBuilder replyBuffer = new StringBuilder();
        Flux<String> stream = llmUtil.streamChat(fullPrompt)
                .doOnNext(replyBuffer::append)
                .concatWith(Flux.defer(() -> {
                    try {
                        if (finalRecordId != null) {
                            healthRecordService.updateAiAdviceById(finalRecordId, replyBuffer.toString());
                        }
                    } catch (Exception e) {
                        log.warn("AI建议更新失败, recordId={}: {}", finalRecordId, e.getMessage());
                    }
                    return Flux.empty();
                }));
        result.setStream(stream);
        return result;
    }

    // ============ 方言鲁棒化辅助方法 ============

    /** 方言同音错字 → 标准健康关键词，提升正则/大模型抽取命中率 */
    private String normalizeDialectKeywords(String text) {
        if (text == null) return text;
        String s = text;
        // 血压：血/学/写 + 呀牙亚哑
        s = s.replaceAll("血[呀牙亚哑]", "血压")
             .replaceAll("学[呀牙亚哑]", "血压")
             .replaceAll("写[呀牙亚哑]", "血压");
        // 血糖：血/写/学 + 唐堂塘
        s = s.replaceAll("血[唐堂塘]", "血糖")
             .replaceAll("写[唐堂塘]", "血糖")
             .replaceAll("学[唐堂塘]", "血糖");
        // 心率：心/新 + 绿吕旅
        s = s.replaceAll("心[绿吕旅]", "心率")
             .replaceAll("新[绿吕旅]", "心率");
        // 体温：体/提 + 问文
        s = s.replaceAll("体[问文]", "体温")
             .replaceAll("提[问文]", "体温");
        // 低压/高压 方言
        s = s.replaceAll("滴[鸭牙]", "低压").replaceAll("低[呀牙]", "低压");
        s = s.replaceAll("高[鸭牙]", "高压");
        return s;
    }

    /** 轻量"是/十"消歧（规则层，与 VoiceAgent 对齐；ASR 已做 LLM 消歧，此处补漏） */
    private String ruleShiShiFix(String text) {
        if (text == null) return text;
        String s = text;
        s = s.replaceAll("([0-9零一二三四五六七八九两十百千万])是([0-9零一二三四五六七八九两点几多来号岁块元分斤个倍])", "$1十$2");
        s = s.replaceAll("是(点|号|岁|块|元|分|斤|度|个|倍)([0-9零一二三四五六七八九两])", "十$1$2");
        s = s.replace("两是", "二十");
        s = s.replaceAll("(我|你|他|她|它|咱|这|那|也|还|都|就|正|才|真|可|又|总|别)十(?![0-9零一二三四五六七八九两十百千万块元岁斤度个倍点号年日月分])", "$1是");
        s = s.replace("十的", "是的");
        return s;
    }

    /** 正则未抽全时，调大模型做结构化抽取并补全缺失指标（解决方言同音错字、中文双值血压） */
    private HealthIntent supplementByLlm(String normText, HealthIntent regex) {
        boolean hasBp = normText.contains("血压") || normText.contains("高压") || normText.contains("低压");
        boolean hasSugar = normText.contains("血糖");
        boolean hasHeart = normText.contains("心率") || normText.contains("心跳") || normText.contains("脉搏");
        boolean hasTemp = normText.contains("体温") || normText.contains("发烧") || normText.contains("发热");
        boolean regexEmpty = regex.systolic == null && regex.diastolic == null
                && regex.heartRate == null && regex.bloodSugar == null && regex.temperature == null;
        boolean need = regexEmpty
                || (hasBp && (regex.systolic == null || regex.diastolic == null))
                || (hasSugar && regex.bloodSugar == null)
                || (hasHeart && regex.heartRate == null)
                || (hasTemp && regex.temperature == null);
        if (!need) return regex;
        HealthIntent llm = llmExtractMetrics(normText);
        if (llm == null) return regex;
        if (regex.systolic == null) regex.systolic = llm.systolic;
        if (regex.diastolic == null) regex.diastolic = llm.diastolic;
        if (regex.heartRate == null) regex.heartRate = llm.heartRate;
        if (regex.bloodSugar == null) regex.bloodSugar = llm.bloodSugar;
        if (regex.temperature == null) regex.temperature = llm.temperature;
        return regex;
    }

    private HealthIntent llmExtractMetrics(String text) {
        try {
            String prompt = "你是医疗语音结构化抽取助手。下面是一段中国方言(四川话/河南话/粤语)或口语的健康描述，"
                    + "方言里'是'(shì)和'十'(shí)常因口音混淆，数值、数量、血压血糖等度量处应为'十'"
                    + "(如'血压是一百六'指160、'血糖十六点五'指16.5、'血压一百六八十'指160/80)。\n"
                    + "请抽取医学指标，只输出一个JSON对象，字段："
                    + "systolic(收缩压整数)、diastolic(舒张压整数)、heartRate(心率整数)、"
                    + "bloodSugar(血糖数字)、temperature(体温数字)。未提及填null。\n"
                    + "只输出JSON，不要解释、不要多余文字。\n原文：" + text;
            String resp = llmUtil.chatSync(prompt);
            if (resp == null || resp.isBlank()) return null;
            int sIdx = resp.indexOf('{');
            int eIdx = resp.lastIndexOf('}');
            if (sIdx < 0 || eIdx <= sIdx) return null;
            String json = resp.substring(sIdx, eIdx + 1);
            Map<String, Object> obj = com.alibaba.fastjson2.JSON.parseObject(json, Map.class);
            if (obj == null) return null;
            HealthIntent it = new HealthIntent();
            it.systolic = optInt(obj, "systolic");
            it.diastolic = optInt(obj, "diastolic");
            it.heartRate = optInt(obj, "heartRate");
            it.bloodSugar = optDouble(obj, "bloodSugar");
            it.temperature = optDouble(obj, "temperature");
            return it;
        } catch (Exception ex) {
            log.warn("大模型结构化抽取健康指标失败: {}", ex.getMessage());
            return null;
        }
    }

    private Integer optInt(Map<String, Object> o, String k) {
        Object v = o.get(k);
        if (v == null) return null;
        try {
            int n = (v instanceof Number) ? ((Number) v).intValue() : Integer.parseInt(v.toString().trim());
            return inRange(n, 30, 300) ? n : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Double optDouble(Map<String, Object> o, String k) {
        Object v = o.get(k);
        if (v == null) return null;
        try {
            double n = (v instanceof Number) ? ((Number) v).doubleValue() : Double.parseDouble(v.toString().trim());
            if ("bloodSugar".equals(k)) return inRange(n, 1.0, 60.0) ? n : null;
            if ("temperature".equals(k)) return inRange(n, 30.0, 45.0) ? n : null;
            return n;
        } catch (Exception e) {
            return null;
        }
    }

    /** 从文本中提取健康指标（支持阿拉伯数字与中文数字：七十/十六点五/一百二） */
    private HealthIntent extractIntentAndMetrics(String userText) {
        HealthIntent intent = new HealthIntent();
        Matcher pressMatch = PRESSURE_PATTERN.matcher(userText);
        if (pressMatch.find()) {
            Integer s = parseCnInt(pressMatch.group(1));
            Integer d = parseCnInt(pressMatch.group(2));
            if (inRange(s, 60, 260)) intent.systolic = s;
            if (inRange(d, 40, 180)) intent.diastolic = d;
        }
        if (intent.systolic == null) {
            Matcher sys = SYS_ONLY_PATTERN.matcher(userText);
            if (sys.find()) {
                Integer v = parseCnInt(sys.group(1));
                if (inRange(v, 60, 260)) intent.systolic = v;
            }
        }
        if (intent.diastolic == null) {
            Matcher dia = DIA_ONLY_PATTERN.matcher(userText);
            if (dia.find()) {
                Integer v = parseCnInt(dia.group(1));
                if (inRange(v, 40, 180)) intent.diastolic = v;
            }
        }
        Matcher sugarMatch = SUGAR_PATTERN.matcher(userText);
        if (sugarMatch.find()) {
            Double v = parseCnDouble(sugarMatch.group(1));
            if (inRange(v, 1.0, 60.0)) intent.bloodSugar = v;
        }
        Matcher heartMatch = HEART_PATTERN.matcher(userText);
        if (heartMatch.find()) {
            Integer v = parseCnInt(heartMatch.group(1));
            if (inRange(v, 30, 220)) intent.heartRate = v;
        }
        Matcher tempMatch = TEMP_PATTERN.matcher(userText);
        if (tempMatch.find()) {
            Double v = parseCnDouble(tempMatch.group(1));
            if (inRange(v, 33.0, 45.0)) intent.temperature = v;
        }
        return intent;
    }

    /** 解析阿拉伯或中文整数（七十→70，七十三→73，十六→16，一百二→102，十六点五→走小数） */
    private Integer parseCnInt(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String s = raw.replaceAll("[多左右大概约几]", "").trim();
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ignore) {
            // 继续尝试中文
        }
        int total = 0, num = 0;
        boolean hasNum = false;
        for (char c : s.toCharArray()) {
            if (c == '点' || c == '.') break; // 整数部分到此为止
            int d = cnDigit(c);
            if (d >= 0) {
                num = d;
                hasNum = true;
            } else if (c == '十') {
                total += (num == 0 && !hasNum) ? 10 : num * 10;
                num = 0;
                hasNum = false;
            } else if (c == '百') {
                total += (num == 0) ? 100 : num * 100;
                num = 0;
                hasNum = false;
            }
        }
        total += num;
        return hasNum ? total : null;
    }

    /** 解析阿拉伯或中文小数（十六点五→16.5，七点二→7.2） */
    private Double parseCnDouble(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String s = raw.replaceAll("[多左右大概约几]", "").trim();
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException ignore) {
            // 继续尝试中文
        }
        if (s.contains("点") || s.contains(".")) {
            String[] parts = s.split("[点\\.]");
            Integer ip = parseCnInt(parts[0]);
            if (ip == null) return null;
            double frac = 0;
            if (parts.length > 1) {
                StringBuilder fb = new StringBuilder();
                for (char c : parts[1].toCharArray()) {
                    int d = cnDigit(c);
                    if (d >= 0) fb.append(d);
                }
                if (fb.length() > 0) {
                    frac = Integer.parseInt(fb.toString()) / Math.pow(10, fb.length());
                }
            }
            return ip + frac;
        }
        Integer i = parseCnInt(s);
        return i == null ? null : i.doubleValue();
    }

    private static boolean inRange(Integer v, int lo, int hi) {
        return v != null && v >= lo && v <= hi;
    }

    private static boolean inRange(Double v, double lo, double hi) {
        return v != null && v >= lo && v <= hi;
    }

    private String buildDataSummary(Integer systolic, Integer diastolic, Integer heartRate,
                                    Double bloodSugar, Double temperature, String riskLevel,
                                    List<HealthRecord> todayRecords, ElderlyUser profile) {
        StringBuilder sb = new StringBuilder();
        if (profile != null) {
            sb.append("老人档案：");
            sb.append("姓名").append(profile.getName() == null ? "老人家" : profile.getName()).append("，");
            sb.append("年龄").append(profile.getAge() == null ? "未知" : profile.getAge()).append("岁，");
            sb.append("病史").append(profile.getRemark() == null || profile.getRemark().isBlank() ? "无" : profile.getRemark()).append("。");
        }
        if (todayRecords != null && !todayRecords.isEmpty()) {
            sb.append("今天已有").append(todayRecords.size()).append("条健康记录。");
        }
        sb.append("本次指标：");
        sb.append("血压").append(systolic != null ? systolic + "/" + diastolic : "未提及").append("，");
        sb.append("血糖").append(bloodSugar != null ? bloodSugar : "未提及").append("，");
        sb.append("心率").append(heartRate != null ? heartRate : "未提及").append("，");
        sb.append("体温").append(temperature != null ? temperature : "未提及").append("。");
        sb.append("风险分级：").append(riskLevel).append("。");
        return sb.toString();
    }

    private void persistAndNotify(HealthRecord record, String userText) {
        try {
            healthRecordService.saveRaw(record);
        } catch (Exception e) {
            log.error("健康记录落库失败，userId={}: {}", record.getUserId(), e.getMessage(), e);
            throw e;
        }
        if ("medium".equals(record.getRiskLevel()) || "high".equals(record.getRiskLevel())) {
            try {
                healthNotifyService.notifyBound(record, userText);
            } catch (Exception e) {
                log.error("健康通知发送失败，userId={}: {}", record.getUserId(), e.getMessage(), e);
                // 通知失败不影响主流程
            }
        }
    }

    private void attachNotifySummary(AgentResult result, HealthRecord record, String userText) {
        if (!"medium".equals(record.getRiskLevel()) && !"high".equals(record.getRiskLevel())) {
            return;
        }
        Map<String, Object> summary = new HashMap<>();
        summary.put("riskLevel", record.getRiskLevel());
        summary.put("recordId", record.getId());
        summary.put("notified", true);
        summary.put("message", "high".equals(record.getRiskLevel())
                ? "已通知绑定家属和护工"
                : "指标异常，已通知绑定家属/护工");
        result.setHealthNotifyJson(JSON.toJSONString(summary));
    }

    static String gradeRisk(Integer systolic, Integer diastolic, Integer heartRate,
                            Double bloodSugar, Double temperature, boolean symptomHigh) {
        HealthRecord record = buildTempRecord(systolic, diastolic, heartRate, bloodSugar, temperature, null);
        if (symptomHigh) {
            return "high";
        }
        return HealthRiskEvaluator.evaluateRisk(record);
    }

    private boolean hitSymptom(String text) {
        return HealthRiskEvaluator.containsHighRiskSymptom(text);
    }

    private static HealthRecord buildTempRecord(Integer systolic, Integer diastolic, Integer heartRate,
                                                Double bloodSugar, Double temperature, String voiceText) {
        HealthRecord record = new HealthRecord();
        record.setSystolicPressure(systolic);
        record.setDiastolicPressure(diastolic);
        record.setHeartRate(heartRate);
        record.setBloodSugar(bloodSugar);
        record.setTemperature(temperature);
        record.setVoiceText(voiceText);
        return record;
    }

    private HealthRecord buildRecord(Long userId, ElderlyUser profile, Integer systolic, Integer diastolic,
                                     Integer heartRate, Double bloodSugar, Double temperature,
                                     String source, String voiceText, String riskLevel) {
        HealthRecord record = new HealthRecord();
        record.setUserId(userId);
        record.setSystolicPressure(systolic);
        record.setDiastolicPressure(diastolic);
        record.setHeartRate(heartRate);
        record.setBloodSugar(bloodSugar);
        record.setTemperature(temperature);
        record.setSource(source);
        record.setVoiceText(voiceText);
        record.setRiskLevel(riskLevel);
        record.setIsAlert("high".equals(riskLevel) ? 1 : 0);
        if (profile != null) {
            record.setElderlyName(profile.getName());
            record.setAge(profile.getAge());
        }
        return record;
    }

    /** 健康意图与指标提取结果 */
    private static class HealthIntent {
        Integer systolic;
        Integer diastolic;
        Integer heartRate;
        Double bloodSugar;
        Double temperature;
    }
}

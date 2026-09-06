package com.elderly.agent;

import com.alibaba.fastjson2.JSON;
import com.elderly.entity.CareOrder;
import com.elderly.entity.ElderlyUser;
import com.elderly.entity.HealthRecord;
import com.elderly.service.ElderlyUserService;
import com.elderly.service.HealthNotifyService;
import com.elderly.service.HealthRecordService;
import com.elderly.util.ChromaUtil;
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

    private static final Pattern PRESSURE_PATTERN = Pattern.compile("(\\d{2,3})\\s*[／/过到]\\s*(\\d{2,3})");
    // 兼容多种口语：血压/高压/低压，可带"是""为""值""：""："等间隔
    private static final Pattern SYS_ONLY_PATTERN = Pattern.compile("(?:血压|高压)[^\\d]{0,8}(\\d{2,3})");
    private static final Pattern SYS_PATTERN = Pattern.compile("高压[^\\d]{0,4}(\\d{2,3})");
    private static final Pattern DIA_PATTERN = Pattern.compile("低压[^\\d]{0,8}(\\d{2,3})");
    private static final Pattern SUGAR_PATTERN = Pattern.compile("(?:血糖|空腹|餐后|餐前)[^\\d]{0,8}(\\d+\\.?\\d*)");
    private static final Pattern HEART_PATTERN = Pattern.compile("(?:心率|心跳|脉搏|心律)[^\\d]{0,8}(\\d{2,3})");
    private static final Pattern TEMP_PATTERN = Pattern.compile("(?:体温|发烧|发热|温度)[^\\d]{0,8}(\\d{1,2}\\.?\\d*)");

    public AgentResult analyseHealth(String userText, Long userId) {
        // ===== 1. 大模型理解意图 + 正则提取指标 =====
        HealthIntent intent = extractIntentAndMetrics(userText);

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
        params.put("question", userText);

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
                    temperature, "voice", userText, riskLevel);
            record.setIsAlert(1);
            record.setAiAdvice(warning);
            persistAndNotify(record, userText);

            AgentResult orderResult = orderDispatchAgent.dispatch(
                    "老人语音上报高危健康情况：" + dataSummary + "。原话：" + userText, userId, true);
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
                    fBloodSugar, fTemperature, "voice", userText, fRisk);
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

    /** 从文本中提取健康指标 */
    private HealthIntent extractIntentAndMetrics(String userText) {
        HealthIntent intent = new HealthIntent();
        Matcher pressMatch = PRESSURE_PATTERN.matcher(userText);
        if (pressMatch.find()) {
            intent.systolic = Integer.parseInt(pressMatch.group(1));
            intent.diastolic = Integer.parseInt(pressMatch.group(2));
        }
        if (intent.systolic == null) {
            Matcher sys = SYS_PATTERN.matcher(userText);
            if (sys.find()) intent.systolic = Integer.parseInt(sys.group(1));
        }
        if (intent.systolic == null) {
            Matcher sysOnly = SYS_ONLY_PATTERN.matcher(userText);
            if (sysOnly.find()) intent.systolic = Integer.parseInt(sysOnly.group(1));
        }
        if (intent.diastolic == null) {
            Matcher dia = DIA_PATTERN.matcher(userText);
            if (dia.find()) intent.diastolic = Integer.parseInt(dia.group(1));
        }
        Matcher sugarMatch = SUGAR_PATTERN.matcher(userText);
        if (sugarMatch.find()) {
            intent.bloodSugar = Double.parseDouble(sugarMatch.group(1));
        }
        Matcher heartMatch = HEART_PATTERN.matcher(userText);
        if (heartMatch.find()) {
            intent.heartRate = Integer.parseInt(heartMatch.group(1));
        }
        Matcher tempMatch = TEMP_PATTERN.matcher(userText);
        if (tempMatch.find()) {
            intent.temperature = Double.parseDouble(tempMatch.group(1));
        }
        return intent;
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

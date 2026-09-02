package com.elderly.service.impl;

import com.elderly.entity.ElderlyUser;
import com.elderly.entity.HealthRecord;
import com.elderly.entity.MedicationReminder;
import com.elderly.service.DailyCareService;
import com.elderly.service.MedicationReminderService;
import com.elderly.util.ChatCacheUtil;
import com.elderly.util.LlmUtil;
import com.elderly.util.PromptUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DailyCareServiceImpl implements DailyCareService {

    private static final Logger log = LoggerFactory.getLogger(DailyCareServiceImpl.class);

    @Resource
    private LlmUtil llmUtil;
    @Resource
    private PromptUtil promptUtil;
    @Resource
    private ChatCacheUtil chatCacheUtil;
    @Resource
    private MedicationReminderService medicationReminderService;

    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate stringRedisTemplate;

    /** Redis 不可用时的降级内存缓存 */
    private static final ConcurrentHashMap<String, Map<String, Object>> LOCAL_CACHE = new ConcurrentHashMap<>();

    private static final String KEY_PREFIX = "today_care:";

    @Override
    public Map<String, Object> generate(ElderlyUser user, List<HealthRecord> todayHealth,
                                        List<HealthRecord> recentHealth, LocalDate today,
                                        boolean forceRefresh) {
        if (user == null) return defaultCare("老人家", "今日暂无健康数据");

        try {
            String cacheKey = buildKey(user.getId(), today);
            if (!forceRefresh) {
                Map<String, Object> cached = readCache(cacheKey);
                if (cached != null) {
                    log.info("今日守护命中缓存, userId={}, cacheKey={}", user.getId(), cacheKey);
                    cached.put("fromCache", true);
                    return cached;
                }
            } else {
                log.info("今日守护强制刷新, userId={}, cacheKey={}", user.getId(), cacheKey);
                clearCache(cacheKey);
            }

            // 当日启用的服药计划（用于关怀文案 + 结构化输出）
            List<MedicationReminder> meds = Collections.emptyList();
            try {
                meds = medicationReminderService.listEnabledByUserId(user.getId());
            } catch (Exception e) {
                log.warn("查询服药计划失败（可能是 medication_reminder 表未创建），降级为空: {}", e.getMessage());
            }
            String timeOfDay = timeOfDay();

        // 数据源说明（前端展示「AI 如何得到这段话」）
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("todayRecordCount", todayHealth == null ? 0 : todayHealth.size());
        meta.put("recentRecordCount", recentHealth == null ? 0 : recentHealth.size());
        boolean anyHighRisk = todayHealth != null && todayHealth.stream()
                .anyMatch(r -> "high".equals(r.getRiskLevel()) || "medium".equals(r.getRiskLevel()));
        meta.put("todayHasRisk", anyHighRisk);
        meta.put("medicationCount", meds == null ? 0 : meds.size());
        meta.put("timeOfDay", timeOfDay);

        String dataSummary = buildDataSummary(user, todayHealth, recentHealth);
        Map<String, Object> generated = invokeLlm(user, dataSummary, meds, timeOfDay);
        if (generated == null) generated = fallback(user, todayHealth, anyHighRisk, meds, timeOfDay);

        // 服药提醒始终以真实计划为准，覆盖模型可能漏写的项
        generated.put("medicationReminders", toMedList(meds));
        generated.put("meta", meta);
        generated.put("fromCache", false);
        generated.put("elderlyName", user.getName() == null ? "老人家" : user.getName());
        generated.put("generateTime", new Date().toString());

        writeCache(cacheKey, generated);
        return generated;
        } catch (Exception e) {
            log.error("今日守护生成异常，返回兜底文案: {}", e.getMessage(), e);
            return defaultCare(user.getName() == null ? "老人家" : user.getName(),
                    "AI 服务暂未返回内容，请稍后重试或检查后端日志");
        }
    }

    /** 拼装今日/近期数据 + 档案给 LLM */
    private String buildDataSummary(ElderlyUser user, List<HealthRecord> today, List<HealthRecord> recent) {
        StringBuilder sb = new StringBuilder();
        sb.append("姓名：").append(user.getName() == null ? "老人家" : user.getName()).append("；");
        sb.append("年龄：").append(user.getAge() == null ? "未知" : user.getAge()).append("；");
        sb.append("病史：").append(user.getRemark() == null || user.getRemark().isBlank() ? "无" : user.getRemark()).append("；");
        sb.append("地址：").append(user.getAddress() == null || user.getAddress().isBlank() ? "—" : user.getAddress()).append("。");

        if (today != null && !today.isEmpty()) {
            sb.append("今日健康记录 ").append(today.size()).append(" 条：");
            int idx = 1;
            for (HealthRecord r : today) {
                sb.append("[").append(idx++).append("] ");
                appendOneRecord(sb, r);
                sb.append("; ");
                if (idx > 5) break;
            }
        } else {
            sb.append("今日尚未记录健康数据。");
        }

        if (recent != null && !recent.isEmpty()) {
            sb.append("近 ").append(recent.size()).append(" 条历史（用于趋势分析）。");
            int abnormal = 0;
            for (HealthRecord r : recent) {
                if ("high".equals(r.getRiskLevel()) || "medium".equals(r.getRiskLevel())) abnormal++;
            }
            sb.append("近 ").append(recent.size()).append(" 条中 ").append(abnormal).append(" 条存在异常。");
        }

        return sb.toString();
    }

    private void appendOneRecord(StringBuilder sb, HealthRecord r) {
        if (r == null) return;
        boolean hasAny = false;
        if (r.getSystolicPressure() != null) {
            sb.append("血压").append(r.getSystolicPressure()).append("/")
                    .append(r.getDiastolicPressure() == null ? "-" : r.getDiastolicPressure()).append(" ");
            hasAny = true;
        }
        if (r.getHeartRate() != null) {
            sb.append("心率").append(r.getHeartRate()).append(" ");
            hasAny = true;
        }
        if (r.getBloodSugar() != null) {
            sb.append("血糖").append(r.getBloodSugar()).append(" ");
            hasAny = true;
        }
        if (r.getTemperature() != null) {
            sb.append("体温").append(r.getTemperature()).append(" ");
            hasAny = true;
        }
        if (r.getRiskLevel() != null && !"low".equals(r.getRiskLevel())) {
            sb.append("(风险:").append(r.getRiskLevel()).append(") ");
        }
        if (!hasAny) sb.append("无指标 ");
    }

    /** 调用 LLM 生成结构化关怀文案；失败时返回 null 以走 fallback */
    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeLlm(ElderlyUser user, String dataSummary,
                                          List<MedicationReminder> meds, String timeOfDay) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("name", user.getName() == null ? "老人家" : user.getName());
            params.put("age", user.getAge() == null ? "" : user.getAge().toString());
            params.put("timeOfDay", timeOfDay);
            params.put("data", dataSummary);
            params.put("medication", formatMeds(meds));
            String prompt = promptUtil.getPrompt("care_today.txt", params);
            String reply = llmUtil.chatSync(prompt);
            if (reply == null || reply.isBlank()) {
                log.warn("今日守护 LLM 返回空，走 fallback; userId={}", user.getId());
                return null;
            }
            log.info("今日守护 LLM 返回长度={}, userId={}", reply.length(), user.getId());

            // 简化解析：要求 LLM 输出 JSON，但做了宽松兜底
            String cleaned = reply;
            if (cleaned.startsWith("```")) {
                int firstLine = cleaned.indexOf('\n');
                int lastFence = cleaned.lastIndexOf("```");
                if (firstLine > 0 && lastFence > firstLine) {
                    cleaned = cleaned.substring(firstLine + 1, lastFence);
                }
            }
            cleaned = cleaned.trim();
            try {
                return com.alibaba.fastjson2.JSON.parseObject(cleaned, Map.class);
            } catch (Exception ignore) {
                // 当成纯文本，包装为标准结构
                Map<String, Object> wrap = new LinkedHashMap<>();
                wrap.put("text", reply);
                wrap.put("bullets", Collections.emptyList());
                wrap.put("medicationReminders", toMedList(meds));
                wrap.put("weatherTip", "今日天气提醒：适量饮水，注意休息。");
                wrap.put("medicationTip", buildMedicationTip(meds));
                wrap.put("source", "AI 关怀（基于今日健康记录 + 服药计划）");
                return wrap;
            }
        } catch (Exception e) {
            log.warn("今日守护 LLM 生成失败，走降级文案: {}", e.getMessage());
            return null;
        }
    }

    /** 模板化兜底文案 —— 保证老人端始终有一句话（且含真实服药提醒） */
    private Map<String, Object> fallback(ElderlyUser user, List<HealthRecord> today,
                                         boolean anyHighRisk, List<MedicationReminder> meds, String timeOfDay) {
        Map<String, Object> m = new LinkedHashMap<>();
        String name = user.getName() == null ? "老人家" : user.getName();
        StringBuilder sb = new StringBuilder();
        sb.append(name).append("，").append(timeOfDay).append("好。");
        if (today == null || today.isEmpty()) {
            sb.append("今天还没收到您的健康数据，空闲时可以测个血压、血糖告诉我一声。");
        } else if (anyHighRisk) {
            sb.append("今天的健康记录有指标偏高的迹象，记得按时吃药，多休息。如有明显不适请立即联系家人或护工。");
        } else {
            sb.append("今天的健康记录整体平稳，请继续保持规律作息，适量运动，记得按时喝水哦。");
        }
        if (meds != null && !meds.isEmpty()) {
            sb.append("今天还有服药安排：").append(formatMeds(meds)).append("，别忘记啦。");
        }
        m.put("text", sb.toString());
        m.put("bullets", toBulletList(meds));
        m.put("medicationReminders", toMedList(meds));
        m.put("weatherTip", "温馨提示：天气较热记得适量饮水，避免长时间户外活动。");
        m.put("medicationTip", buildMedicationTip(meds));
        m.put("source", "AI 关怀（基于今日健康数据 + 服药计划 + 档案）");
        return m;
    }

    private Map<String, Object> defaultCare(String name, String tip) {
        Map<String, Object> m = new LinkedHashMap<>();
        String n = name == null || name.isBlank() ? "老人家" : name;
        m.put("text", n + "，今天请记得按时服药、适量饮水，保持好心情。" + tip);
        m.put("bullets", Arrays.asList("按时服药", "适量饮水", "不适及时联系家人"));
        m.put("medicationReminders", Collections.emptyList());
        m.put("weatherTip", "温馨提示：今天也要照顾好自己哦。");
        m.put("medicationTip", "按时服药，不漏服、不多服。");
        m.put("source", "银龄智护每日关怀");
        m.put("meta", Collections.emptyMap());
        return m;
    }

    /** 当前时段：凌晨/上午/中午/下午/晚上 */
    private String timeOfDay() {
        int h = LocalTime.now().getHour();
        if (h < 6) return "凌晨";
        if (h < 11) return "上午";
        if (h < 13) return "中午";
        if (h < 18) return "下午";
        return "晚上";
    }

    /** 把服药计划格式化为可读串，例如 "1. 08:00 降压药(1片)；2. 21:00 安神药(1片)；" */
    private String formatMeds(List<MedicationReminder> meds) {
        if (meds == null || meds.isEmpty()) return "（无，老人未设置服药计划）";
        StringBuilder sb = new StringBuilder();
        int idx = 1;
        for (MedicationReminder m : meds) {
            if (m.getDoseTime() == null) continue;
            sb.append(idx++).append(". ")
                    .append(m.getDoseTime().format(DateTimeFormatter.ofPattern("HH:mm"))).append(" ")
                    .append(m.getDrugName() == null ? "药" : m.getDrugName()).append(" ")
                    .append((m.getDosage() == null || m.getDosage().isBlank()) ? "" : ("(" + m.getDosage() + ")"))
                    .append("；");
        }
        return sb.toString();
    }

    /** 结构化服药列表（供前端渲染） */
    private List<Map<String, String>> toMedList(List<MedicationReminder> meds) {
        List<Map<String, String>> list = new ArrayList<>();
        if (meds == null) return list;
        for (MedicationReminder m : meds) {
            if (m.getDoseTime() == null) continue;
            Map<String, String> item = new LinkedHashMap<>();
            item.put("time", m.getDoseTime().format(DateTimeFormatter.ofPattern("HH:mm")));
            item.put("drug", m.getDrugName() == null ? "" : m.getDrugName());
            item.put("dosage", m.getDosage() == null ? "" : m.getDosage());
            list.add(item);
        }
        return list;
    }

    private List<String> toBulletList(List<MedicationReminder> meds) {
        List<String> list = new ArrayList<>();
        if (meds == null) return list;
        for (MedicationReminder m : meds) {
            if (m.getDoseTime() == null) continue;
            list.add(m.getDoseTime().format(DateTimeFormatter.ofPattern("HH:mm")) + " "
                    + (m.getDrugName() == null ? "服药" : m.getDrugName())
                    + ((m.getDosage() == null || m.getDosage().isBlank()) ? "" : "（" + m.getDosage() + "）"));
        }
        return list;
    }

    private String buildMedicationTip(List<MedicationReminder> meds) {
        if (meds == null || meds.isEmpty()) return "按时服药，有不适随时联系家人。";
        return "按时服药：" + formatMeds(meds) + "如有不适随时联系家人。";
    }

    private String buildKey(Long userId, LocalDate today) {
        return KEY_PREFIX + (userId == null ? "anon" : userId) + ":" + today.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readCache(String key) {
        try {
            String json = stringRedisTemplate.opsForValue().get(key);
            if (json != null) {
                return com.alibaba.fastjson2.JSON.parseObject(json, Map.class);
            }
        } catch (Exception e) {
            log.debug("Redis 读失败，使用本地缓存: {}", e.getMessage());
        }
        Map<String, Object> local = LOCAL_CACHE.get(key);
        if (local != null && local.get("__ts") instanceof Long ts) {
            if (System.currentTimeMillis() - ts > Duration.ofMinutes(30).toMillis()) {
                LOCAL_CACHE.remove(key);
                return null;
            }
        }
        return local == null ? null : new LinkedHashMap<>(local);
    }

    private void writeCache(String key, Map<String, Object> data) {
        try {
            stringRedisTemplate.opsForValue().set(key,
                    com.alibaba.fastjson2.JSON.toJSONString(data),
                    Duration.ofMinutes(30));
            return;
        } catch (Exception e) {
            log.debug("Redis 写失败，使用本地缓存: {}", e.getMessage());
        }
        Map<String, Object> copy = new LinkedHashMap<>(data);
        copy.put("__ts", System.currentTimeMillis());
        LOCAL_CACHE.put(key, copy);
    }

    private void clearCache(String key) {
        try {
            stringRedisTemplate.delete(key);
        } catch (Exception e) {
            log.debug("Redis 清除缓存失败: {}", e.getMessage());
        }
        LOCAL_CACHE.remove(key);
    }
}

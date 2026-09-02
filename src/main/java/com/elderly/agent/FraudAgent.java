package com.elderly.agent;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.elderly.entity.ElderlyUser;
import com.elderly.entity.FamilyBind;
import com.elderly.entity.FraudAlert;
import com.elderly.mapper.FamilyBindMapper;
import com.elderly.service.ElderlyUserService;
import com.elderly.service.FraudAlertService;
import com.elderly.service.WxSubscribeService;
import com.elderly.util.ChromaUtil;
import com.elderly.util.FraudKeywordUtil;
import com.elderly.util.LlmUtil;
import com.elderly.util.PromptUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Agent3：老年反诈识别智能体（AI + RAG 双重防火墙）
 *
 * 标准链路（与用户要求的流程完全对应）：
 *   内容识别（用户说/老人听到的话）
 *      ↓
 *   AI 诈骗场景分析（大模型语义判别 + Few-Shot）
 *      ↓
 *   RAG 诈骗案例库比对（Chroma 向量库 Top3）
 *      ↓
 *   风险等级综合判定（high/medium/low）
 *      ↓
 *   输出：落库 fraud_alert + 全屏红色警告 + 反诈顺口溜语音播报
 *
 * 提示：本地反诈案例库 RAG 服务不可用时降级纯 LLM 判别，不影响主流程。
 */
@Service
public class FraudAgent {

    private static final Logger log = LoggerFactory.getLogger(FraudAgent.class);

    @Resource
    private FraudKeywordUtil keywordUtil;
    @Resource
    private LlmUtil llmUtil;
    @Resource
    private PromptUtil promptUtil;
    @Resource
    private FraudAlertService fraudAlertService;
    @Resource
    private ElderlyUserService userService;
    @Resource
    private ChromaUtil chromaUtil;
    @Resource
    private FamilyBindMapper familyBindMapper;
    @Resource
    private WxSubscribeService wxSubscribeService;

    /** 反诈顺口溜库（命中时随机播报） */
    private static final List<String> JINGLES = List.of(
            "陌生电话要转账，一律挂断不商量；天上不会掉馅饼，守好钱袋才稳当。",
            "保健品不是药，包治百病是圈套；免费领鸡蛋要小心，兜里钱包捂捂紧。",
            "高息理财别眼红，血本无归一场空；不听不信不转账，遇到诈骗打110。",
            "神药特效都是假，医院检查才不差；辛辛苦苦养老钱，别给骗子发年终奖。"
    );

    public AgentResult checkFraud(String text, Long userId) {
        if (text == null || text.isBlank()) {
            return AgentResult.of("fraud", Flux.just("您什么都没说呢，我在这里等您。"));
        }

        // ===== 第 1 步：内容识别（已识别出文本） =====
        String contentRecognized = text.length() > 80 ? text.substring(0, 80) + "…" : text;

        // ===== 第 1.5 步：本地关键词快速命中（第一层防火墙） =====
        List<String> keywordHits = keywordUtil.hitWords(text);
        boolean keywordHit = !keywordHits.isEmpty();

        // ===== 第 2 步：RAG 诈骗案例库比对（提前到 AI 分析前，便于 AI 失败时兜底） =====
        List<String> matchedCases = new ArrayList<>();
        try {
            matchedCases = chromaUtil.searchFraudCase(text);
        } catch (Exception e) {
            log.warn("反诈 RAG 检索失败（Chroma 不可用时降级）: {}", e.getMessage());
        }
        boolean ragHit = matchedCases != null && !matchedCases.isEmpty();

        // ===== 第 3 步：AI 诈骗场景分析 =====
        String aiAnalysis = "";
        String aiRiskLevel = "low";
        String aiOutput = "";
        boolean aiHit = false;
        try {
            String ragContext = retrieveFraudContext(text);
            Map<String, String> param = new HashMap<>();
            param.put("context", ragContext.isBlank() ? "（暂无本地案例库，请基于通用常识判别）" : ragContext);
            param.put("text", text);
            String prompt = promptUtil.getPrompt("fraud.txt", param);
            String aiVerdict = llmUtil.chatSync(prompt);
            if (aiVerdict == null) aiVerdict = "";
            aiVerdict = aiVerdict.trim();
            JSONObject parsed = tryParseJson(aiVerdict);
            if (parsed != null) {
                aiAnalysis = parsed.getString("aiAnalysis");
                aiOutput = parsed.getString("output");
                aiRiskLevel = parsed.getString("riskLevel");
                if (aiRiskLevel == null) aiRiskLevel = "low";
            } else {
                // 兼容旧版纯文本输出
                aiAnalysis = aiVerdict.contains("风险") ? "AI 识别到疑似诈骗话术" : "AI 判断为安全内容";
                aiOutput = aiVerdict;
            }
            aiHit = "high".equals(aiRiskLevel) || "medium".equals(aiRiskLevel) || aiVerdict.contains("风险");
        } catch (Exception e) {
            log.warn("反诈 AI 语义判别异常, text={}: {}", text, e.getMessage(), e);
            aiAnalysis = buildAiFailAnalysis(keywordHits, matchedCases);
        }

        // ===== 第 4 步：风险等级综合判定 =====
        String riskLevel = resolveRiskLevel(aiHit, aiRiskLevel, ragHit, keywordHit);
        String detectType = resolveDetectType(aiHit, ragHit, keywordHit);

        String jingle = JINGLES.get(ThreadLocalRandom.current().nextInt(JINGLES.size()));

        // ===== 第 5 步：输出 =====
        if ("low".equals(riskLevel)) {
            String safeReply = aiOutput != null && !aiOutput.isBlank()
                    ? aiOutput
                    : "这段话没有发现诈骗风险，您可以放心。不过遇到让您掏钱的事，记得先和子女商量。";
            return AgentResult.of("fraud", Flux.just(safeReply));
        }

        String tip = buildTip(riskLevel, text, aiAnalysis, matchedCases);
        String finalReply = (aiOutput != null && !aiOutput.isBlank()) ? aiOutput : jingle;

        // 命中：落库 + 全屏 + 顺口溜
        String hitKeywords = buildHitKeywords(aiHit, ragHit, matchedCases, keywordHits);
        saveAlert(userId, text, hitKeywords, riskLevel, detectType, tip, aiAnalysis, matchedCases);

        JSONObject risk = new JSONObject();
        risk.put("level", riskLevel);
        risk.put("detectType", detectType);
        risk.put("contentRecognized", contentRecognized);
        risk.put("aiAnalysis", aiAnalysis);
        risk.put("ragHit", ragHit);
        risk.put("matchedCases", matchedCases);
        risk.put("tip", tip);
        risk.put("jingle", jingle);

        AgentResult result = AgentResult.of("fraud", Flux.just(finalReply));
        result.setRiskJson(risk.toJSONString());
        result.setRiskLevel(riskLevel);
        result.setMatchedCases(matchedCases);
        return result;
    }

    /** 解析 AI 返回的 JSON，失败返回 null */
    private JSONObject tryParseJson(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            // 去掉可能的 Markdown 代码块标记
            String clean = text.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            return JSON.parseObject(clean);
        } catch (Exception e) {
            return null;
        }
    }

    /** Chroma 检索反诈案例上下文，供 AI prompt 使用 */
    private String retrieveFraudContext(String text) {
        try {
            List<String> list = chromaUtil.searchFraudCase(text);
            if (list == null || list.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            int i = 1;
            for (String c : list) {
                if (c == null) continue;
                String trimmed = c.length() > 240 ? c.substring(0, 240) + "…" : c;
                sb.append("案例").append(i++).append("：").append(trimmed).append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private String resolveRiskLevel(boolean aiHit, String aiRiskLevel, boolean ragHit, boolean keywordHit) {
        // AI 明确判 high，直接 high
        if ("high".equals(aiRiskLevel)) return "high";
        // AI 判 medium 且 RAG 命中，升级为 high
        if ("medium".equals(aiRiskLevel) && ragHit) return "high";
        // AI 判 medium 或 RAG 命中，均为 medium
        if ("medium".equals(aiRiskLevel) || ragHit) return "medium";
        // 兜底：本地关键词命中但 AI 未识别，至少判 medium
        if (keywordHit) return "medium";
        // 兼容旧版 hit 语义
        if (aiHit) return "medium";
        return "low";
    }

    private String resolveDetectType(boolean aiHit, boolean ragHit, boolean keywordHit) {
        if (aiHit && ragHit) return "ai+rag";
        if (aiHit) return "ai";
        if (ragHit) return "rag";
        if (keywordHit) return "keyword";
        return "none";
    }

    private String buildHitKeywords(boolean aiHit, boolean ragHit, List<String> matchedCases, List<String> keywordHits) {
        StringBuilder sb = new StringBuilder();
        if (aiHit) sb.append("AI语义识别");
        if (keywordHits != null && !keywordHits.isEmpty()) {
            if (sb.length() > 0) sb.append(",");
            sb.append("关键词:").append(String.join(",", keywordHits));
        }
        if (ragHit) {
            if (sb.length() > 0) sb.append(",");
            sb.append("RAG相似案例×").append(matchedCases.size());
        }
        return sb.toString();
    }

    private String buildTip(String riskLevel, String text, String aiAnalysis, List<String> matchedCases) {
        StringBuilder sb = new StringBuilder();
        if ("high".equals(riskLevel)) {
            sb.append("【高风险警告】AI 诈骗场景分析 + RAG 案例库比对均识别出这段话存在诈骗嫌疑。");
        } else {
            sb.append("【风险提示】AI 检测到这段话存在夸大宣传或骗局嫌疑。");
        }
        if (aiAnalysis != null && !aiAnalysis.isBlank()) {
            sb.append("分析：").append(aiAnalysis).append("；");
        }
        sb.append("请不要轻信、不要转账，先和子女或社区民警确认。");
        if (matchedCases != null && !matchedCases.isEmpty()) {
            sb.append("（反诈案例库命中 ").append(matchedCases.size()).append(" 条相似案例）");
        }
        return sb.toString();
    }

    /** AI 语义判别失败时，根据关键词/RAG 命中情况生成更具体的分析文案 */
    private String buildAiFailAnalysis(List<String> keywordHits, List<String> matchedCases) {
        StringBuilder sb = new StringBuilder();
        if (keywordHits != null && !keywordHits.isEmpty()) {
            sb.append("命中风险关键词：").append(String.join("、", keywordHits)).append("；");
        }
        if (matchedCases != null && !matchedCases.isEmpty()) {
            sb.append("反诈案例库命中 ").append(matchedCases.size()).append(" 条相似案例；");
        }
        if (sb.length() == 0) {
            sb.append("AI 分析暂不可用，已使用关键词+RAG兜底判别；");
        }
        sb.append("仍判定存在诈骗嫌疑");
        return sb.toString();
    }

    private void saveAlert(Long userId, String content, String hitKeywords,
                           String riskLevel, String detectType, String aiTip,
                           String aiVerdict, List<String> matchedCases) {
        try {
            FraudAlert alert = new FraudAlert();
            alert.setUserId(userId);
            alert.setContent(content);
            alert.setHitKeywords(hitKeywords);
            alert.setRiskLevel(riskLevel);
            alert.setDetectType(detectType);
            StringBuilder tip = new StringBuilder(aiTip == null ? "" : aiTip);
            if (aiVerdict != null && !aiVerdict.isBlank()) {
                tip.append("\n[AI判别]").append(aiVerdict);
            }
            if (matchedCases != null && !matchedCases.isEmpty()) {
                tip.append("\n[RAG命中]").append(matchedCases.size()).append("条相似案例");
                for (int i = 0; i < Math.min(matchedCases.size(), 2); i++) {
                    String c = matchedCases.get(i);
                    if (c != null && !c.isBlank()) {
                        String snippet = c.length() > 80 ? c.substring(0, 80) + "…" : c;
                        tip.append(" / ").append(snippet);
                    }
                }
            }
            alert.setAiTip(tip.toString());
            if (matchedCases != null && !matchedCases.isEmpty()) {
                StringBuilder cases = new StringBuilder();
                int cnt = 0;
                for (String c : matchedCases) {
                    if (c == null) continue;
                    String snippet = c.length() > 300 ? c.substring(0, 300) : c;
                    if (cases.length() > 0) cases.append("||");
                    cases.append(snippet);
                    if (++cnt >= 3) break;
                }
                alert.setMatchedCases(cases.toString());
            }
            if (userId != null) {
                ElderlyUser user = userService.getById(userId);
                if (user != null) {
                    alert.setElderlyName(user.getName());
                }
            }
            fraudAlertService.save(alert);
            log.info("反诈预警已落库: level={}, detectType={}, keywords={}",
                    riskLevel, detectType, hitKeywords);

            // 向绑定家属推送反诈风险检测订阅消息（失败不影响主流程）
            if (userId != null && ("high".equals(riskLevel) || "medium".equals(riskLevel))) {
                pushFraudSubscribeMessage(userId, content, riskLevel, aiTip);
            }
        } catch (Exception e) {
            log.warn("反诈记录落库失败: {}", e.getMessage());
        }
    }

    private void pushFraudSubscribeMessage(Long userId, String content, String riskLevel, String advice) {
        try {
            List<FamilyBind> binds = familyBindMapper.selectEnabledByElderlyUserId(userId);
            if (binds == null || binds.isEmpty()) {
                return;
            }
            String levelText = "high".equals(riskLevel) ? "高" : "中";
            String contentText = content == null ? "检测到疑似诈骗话术" : content;
            if (contentText.length() > 20) {
                contentText = contentText.substring(0, 19) + "…";
            }
            String adviceText = advice == null || advice.isBlank()
                    ? "请立即联系老人核实，不要轻信转账要求"
                    : advice;
            if (adviceText.length() > 20) {
                adviceText = adviceText.substring(0, 19) + "…";
            }
            for (FamilyBind b : binds) {
                if (b == null || b.getOpenId() == null || b.getOpenId().isBlank()) {
                    continue;
                }
                try {
                    wxSubscribeService.sendFraudRisk(b.getOpenId(), contentText, levelText, adviceText);
                } catch (Exception ex) {
                    log.warn("反诈订阅消息发送失败 openId={}: {}", b.getOpenId(), ex.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("反诈订阅消息推送异常 userId={}: {}", userId, e.getMessage());
        }
    }
}

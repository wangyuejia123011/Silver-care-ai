package com.elderly.agent;

import com.alibaba.fastjson2.JSON;
import com.elderly.entity.ChatLog;
import com.elderly.service.ChatLogService;
import com.elderly.util.BaiduSpeechUtil;
import com.elderly.util.FraudKeywordUtil;
import com.elderly.util.LlmUtil;
import com.elderly.util.PromptUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent1：方言语音交互智能体（统一调度入口）
 * 链路：ASR方言识别 → 低置信度文本清洗 → 意图分类(voice.txt) → 路由子Agent → SSE流式事件
 *
 * SSE事件协议（前端据此渲染）：
 *   event:asr    语音识别出的文本
 *   event:intent 意图标签：健康/服务/反诈/闲聊
 *   event:token  AI回复片段（打字机拼接）
 *   event:risk   反诈风险JSON（前端弹全屏红色警告）
 *   event:order  自动生成的工单JSON（高危/服务请求）
 *   event:emotion 情绪标签：低落/正常
 *   event:done   完整回复全文（前端可拿去TTS播报）
 *   event:error  错误消息
 */
@Service
public class VoiceAgent {

    private static final Logger log = LoggerFactory.getLogger(VoiceAgent.class);

    @Resource
    private BaiduSpeechUtil speechUtil;
    @Resource
    private PromptUtil promptUtil;
    @Resource
    private LlmUtil llmUtil;
    @Resource
    private ChatLogService chatLogService;
    @Resource
    private FraudKeywordUtil fraudKeywordUtil;

    @Resource
    private HealthAgent healthAgent;
    @Resource
    private FraudAgent fraudAgent;
    @Resource
    private EmotionAgent emotionAgent;
    @Resource
    private OrderDispatchAgent orderDispatchAgent;

    /**
     * 仅ASR识别（小程序两步式语音链路第一步：录音→文本）
     * 返回清洗后的标准文本
     */
    public java.util.Map<String, Object> asrOnly(byte[] audioBytes, String dialect) {
        java.util.Map<String, Object> result = new HashMap<>();
        HashMap<String, Object> asrResult;
        try {
            asrResult = speechUtil.asr(audioBytes, dialect);
        } catch (Exception e) {
            log.error("ASR识别异常: {}", e.getMessage());
            result.put("text", "");
            result.put("score", 0);
            return result;
        }
        String rawText = (String) asrResult.getOrDefault("text", "");
        // 百度新版 AIP SDK 不返回 score 字段，hasScore=false 表示置信度未知，
        // 此时不应触发大模型清洗（否则会破坏百度本身已准确的识别结果）
        boolean hasScore = asrResult.containsKey("score");
        double score = 0;
        if (hasScore) {
            try {
                score = Double.parseDouble(asrResult.get("score").toString());
            } catch (Exception ignored) {
            }
        }
        result.put("score", hasScore ? score : -1);
        result.put("rawText", rawText);

        if (rawText == null || rawText.isBlank()) {
            result.put("text", "");
            // 透传百度原始错误信息，便于前端排查
            if (asrResult.containsKey("err_msg")) {
                result.put("error", asrResult.get("err_msg"));
            }
            return result;
        }

        String finalText = rawText;
        // 仅在百度明确给出低置信度分数时才做最小纠错，且要求不改变原意、不增减内容，
        // 避免对高准确识别结果做无谓的大模型改写（这是此前"识别不准确"的主因）
        if (hasScore && score < 0.6) {
            try {
                String clean = llmUtil.chatSync(
                        "在不改变原意、不增减内容的前提下，仅修正明显错别字或语法错误，保留口语化表达："
                                + rawText);
                clean = (clean == null ? "" : clean).trim();
                finalText = clean.isBlank() ? rawText : clean;
            } catch (Exception ignored) {
            }
        }
        // 方言"是/十"同音消歧：四川话等口音中极易混淆，需在进入意图分类前修正
        finalText = fixShiShi(finalText);
        result.put("text", finalText);
        return result;
    }

    /**
     * 语音入口：音频字节 + 方言类型 → SSE事件流
     */
    public Flux<ServerSentEvent<String>> handleAudio(byte[] audioBytes, String dialect, Long userId) {
        return Flux.defer(() -> {
            // 1. 百度ASR方言识别
            HashMap<String, Object> asrResult;
            try {
                asrResult = speechUtil.asr(audioBytes, dialect);
            } catch (Exception e) {
                log.error("ASR识别异常: {}", e.getMessage());
                return Flux.just(sse("error", "语音识别失败，请重试或改用文字输入。"));
            }
            String rawText = (String) asrResult.getOrDefault("text", "");
            boolean hasScore = asrResult.containsKey("score");
            double score = 0;
            if (hasScore) {
                try {
                    score = Double.parseDouble(asrResult.get("score").toString());
                } catch (Exception ignored) {
                }
            }

            if (rawText == null || rawText.isBlank()) {
                return Flux.just(sse("error", "没有听清您说的话，请靠近一点再说一遍。"));
            }

            // 2. 仅低置信度时做最小纠错（同上，避免无谓改写）
            String cleanText = rawText;
            if (hasScore && score < 0.6) {
                try {
                    cleanText = llmUtil.chatSync(
                            "在不改变原意、不增减内容的前提下，仅修正明显错别字或语法错误，保留口语化表达："
                                    + rawText);
                    cleanText = (cleanText == null ? "" : cleanText).trim();
                    if (cleanText.isBlank()) cleanText = rawText;
                } catch (Exception e) {
                    cleanText = rawText;
                }
            }
            // 2.5 方言"是/十"同音消歧（四川话等口音中极易混淆）
            cleanText = fixShiShi(cleanText);

            // 3. 推送识别结果 + 意图路由流
            return Flux.concat(
                    Flux.just(sse("asr", cleanText)),
                    buildDispatchFlow(cleanText, userId, "voice")
            );
        });
    }

    /**
     * 文字入口：与语音共用同一套路由（统一调度中台）
     */
    public Flux<ServerSentEvent<String>> handleText(String text, Long userId) {
        return Flux.defer(() -> buildDispatchFlow(text.trim(), userId, "text"));
    }

    /**
     * 健康管理专用入口：跳过意图分类，直接走 HealthAgent
     */
    public Flux<ServerSentEvent<String>> handleHealthText(String text, Long userId) {
        return Flux.defer(() -> {
            AgentResult agentResult;
            try {
                agentResult = healthAgent.analyseHealth(text.trim(), userId);
            } catch (Exception e) {
                log.error("健康分析异常: {}", e.getMessage(), e);
                return Flux.just(sse("error", "健康分析暂时异常，请稍后再试。"));
            }
            return assembleFlow("健康", agentResult, text.trim(), userId, "voice");
        });
    }

    /**
     * 统一调度：意图分类 → 路由子Agent → 组装SSE事件流
     */
    private Flux<ServerSentEvent<String>> buildDispatchFlow(String text, Long userId, String source) {
        // 前置反诈关键词防火墙：命中风险词直接走反诈，避免 LLM 误判为健康/闲聊
        if (fraudKeywordUtil.hitRiskWord(text)) {
            log.info("反诈关键词前置命中，强制路由到 FraudAgent: text={}", text);
            AgentResult agentResult = fraudAgent.checkFraud(text, userId);
            return assembleFlow("反诈", agentResult, text, userId, source);
        }

        String intent = classifyIntent(text);
        AgentResult agentResult;
        try {
            agentResult = switch (intent) {
                case "健康" -> healthAgent.analyseHealth(text, userId);
                case "反诈" -> fraudAgent.checkFraud(text, userId);
                case "服务" -> orderDispatchAgent.dispatch(text, userId, false);
                default -> emotionAgent.chatEmotion(text);
            };
        } catch (Exception e) {
            log.error("Agent调度异常: intent={}, {}", intent, e.getMessage(), e);
            return Flux.just(sse("error", "AI服务暂时异常，请稍后再试。"));
        }
        return assembleFlow(intent, agentResult, text, userId, source);
    }

    private Flux<ServerSentEvent<String>> assembleFlow(String intent, AgentResult agentResult,
                                                       String text, Long userId, String source) {
        List<ServerSentEvent<String>> preEvents = new ArrayList<>();
        preEvents.add(sse("intent", intent));
        if (agentResult.getRiskJson() != null) {
            preEvents.add(sse("risk", agentResult.getRiskJson()));
        }
        if (agentResult.getOrder() != null) {
            preEvents.add(sse("order", JSON.toJSONString(agentResult.getOrder())));
        }
        if (agentResult.getHealthNotifyJson() != null) {
            preEvents.add(sse("notify", agentResult.getHealthNotifyJson()));
        }

        StringBuilder replyBuffer = new StringBuilder();
        Flux<ServerSentEvent<String>> tokenFlux = agentResult.getStream()
                .doOnNext(replyBuffer::append)
                .map(t -> sse("token", t));

        String chatType = agentResult.getChatType();
        String emotion = agentResult.getEmotion();
        String input = text;

        Flux<ServerSentEvent<String>> tail = Flux.defer(() -> {
            List<ServerSentEvent<String>> tailEvents = new ArrayList<>();
            if (agentResult.getHealthNotifyJson() != null) {
                boolean already = preEvents.stream().anyMatch(e -> "notify".equals(e.event()));
                if (!already) {
                    tailEvents.add(sse("notify", agentResult.getHealthNotifyJson()));
                }
            }
            if (emotion != null) {
                tailEvents.add(sse("emotion", emotion));
            }
            tailEvents.add(sse("done", replyBuffer.toString()));
            saveChatLog(userId, chatType, input, replyBuffer.toString(), emotion, source);
            return Flux.fromIterable(tailEvents);
        });

        return Flux.concat(Flux.fromIterable(preEvents), tokenFlux, tail);
    }

    /** 意图分类（voice.txt），带容错归一化 */
    private String classifyIntent(String text) {
        try {
            Map<String, String> param = new HashMap<>();
            param.put("text", text);
            String intentPrompt = promptUtil.getPrompt("voice.txt", param);
            String tag = llmUtil.chatSync(intentPrompt);
            tag = (tag == null ? "" : tag).trim();
            if (tag.contains("健康") || tag.contains("health")) return "健康";
            if (tag.contains("反诈") || tag.contains("诈骗") || tag.contains("fraud")) return "反诈";
            if (tag.contains("服务") || tag.contains("工单") || tag.contains("order")) return "服务";
            return "闲聊";
        } catch (Exception e) {
            log.warn("意图分类异常，按闲聊处理: {}", e.getMessage());
            return "闲聊";
        }
    }

    /**
     * 方言"是/十"同音消歧：方言（尤其四川话）口音中"是"(shì)与"十"(shí)极易被ASR混淆。
     * 先以规则高置信修补数字/量词相邻情形（零延迟、不依赖大模型），再对疑似混淆文本做一次大模型上下文消歧。
     */
    private String fixShiShi(String text) {
        if (text == null || text.isBlank()) return text;
        // 1. 规则层：高置信度数字/度量相邻情形，零延迟、即使大模型不可用也能拦住多数数值场景
        String fixed = ruleFixShiShi(text);
        // 2. 大模型层：原文或规则后仍疑似"是/十"混淆时，做一次上下文消歧
        if (needsShiShiFix(text) || needsShiShiFix(fixed)) {
            try {
                String prompt = "你是语音识别纠错助手。以下是一段中国方言(四川话/河南话/粤语等)经语音识别得到的文本，"
                        + "方言口音中\"是\"(shì)与\"十\"(shí)声调相近极易混淆。请仅依据上下文判断并修正二者的混淆：\n"
                        + "1) 数值、数量、年龄、时间、价格、以及血压/血糖/心率/体温/体重等健康度量表述中应为\"十\"，"
                        + "例如：十六、十点五、十岁、十块、十号、一百六十；\n"
                        + "2) 判断词、系词、肯定回答处应为\"是\"，例如：我是、也是、还是、是的、他是。\n"
                        + "3) 若某处上下文既像数量又像系词、无法明确判定，优先保留\"是\"（口语中系词\"是\"远多于数字\"十\"）。\n"
                        + "其余所有文字、标点、口语化表达一律不得改动，不得增删任何内容。\n"
                        + "只输出修正后的文本本身，不要解释、不要引号、不要换行。\n原文：" + fixed;
                String corrected = llmUtil.chatSync(prompt);
                corrected = (corrected == null ? "" : corrected).trim();
                if (!corrected.isBlank() && !corrected.equals(fixed)
                        && Math.abs(corrected.length() - fixed.length()) <= 3) {
                    log.info("是/十 消歧修正: [{}] -> [{}]", fixed, corrected);
                    return corrected;
                }
            } catch (Exception e) {
                log.warn("是/十 消歧LLM调用失败，沿用规则结果: {}", e.getMessage());
            }
        }
        return fixed;
    }

    /** 是否需要触发大模型"是/十"消歧：含"十"或"是"出现在数字/度量相邻上下文 */
    private boolean needsShiShiFix(String t) {
        if (t == null || t.isBlank()) return false;
        if (t.contains("十")) return true;
        return t.matches(".*[0-9零一二三四五六七八九两十百千万]是.*")
                || t.matches(".*是[0-9零一二三四五六七八九两点号岁块元分斤个倍度].*")
                || t.matches(".*(血压|血糖|心率|体温|体重|年龄|高压|低压|度数)是.*");
    }

    /**
     * 规则层"是/十"双向修补（高置信、不误伤）：
     * A) 是 → 十（数值/度量上下文）：一十六→一是六、是点五→十点五、两是→二十
     * B) 十 → 是（判断词/系词上下文，带负向预查排除"十块/十岁"等真实数字）：
     *    我十→我是、还十→还是、也十→也是、就十→就是、十的→是的
     */
    private String ruleFixShiShi(String text) {
        String s = text;
        // A) 是 → 十：数字 + 是 + 数字/小数/几多来
        s = s.replaceAll("([0-9零一二三四五六七八九两十百千万])是([0-9零一二三四五六七八九两点几多来])", "$1十$2");
        // A2) 是 → 十：是 + 量词 + 数字（是点五→十点五、是块五→十块五）
        s = s.replaceAll("是(点|号|岁|块|元|分|斤|度|个|倍)([0-9零一二三四五六七八九两])", "十$1$2");
        s = s.replace("两是", "二十");
        // B) 十 → 是：代词/系词副词 + 十，且"十"后不是数字或量词（排除我十块/我十岁等真实数量）
        s = s.replaceAll("(我|你|他|她|它|咱|这|那|也|还|都|就|正|才|真|可|又|总|别)十"
                + "(?![0-9零一二三四五六七八九两十百千万块元岁斤度个倍点号年日月分])", "$1是");
        s = s.replace("十的", "是的");
        return s;
    }

    /** 文字生成完成后TTS音频（供语音播报） */
    public byte[] generateVoiceAudio(String fullText) {
        return speechUtil.tts(fullText);
    }

    private void saveChatLog(Long userId, String chatType, String input, String reply,
                             String emotion, String source) {
        try {
            ChatLog chatLog = new ChatLog();
            chatLog.setUserId(userId);
            chatLog.setChatType(chatType);
            chatLog.setUserInput(input);
            chatLog.setAiReply(reply);
            chatLog.setEmotion(emotion);
            chatLog.setSource(source);
            chatLogService.save(chatLog);
        } catch (Exception e) {
            log.warn("对话记录落库失败: {}", e.getMessage());
        }
    }

    private ServerSentEvent<String> sse(String event, String data) {
        return ServerSentEvent.<String>builder().event(event).data(data == null ? "" : data).build();
    }
}

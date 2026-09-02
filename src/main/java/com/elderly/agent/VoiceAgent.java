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
        double score = 0;
        try {
            score = Double.parseDouble(asrResult.getOrDefault("score", "0").toString());
        } catch (Exception ignored) {
        }
        result.put("score", score);
        result.put("rawText", rawText);

        if (rawText == null || rawText.isBlank()) {
            result.put("text", "");
            return result;
        }

        // 置信度低：大模型清洗方言口语
        if (score < 0.8) {
            try {
                String clean = llmUtil.chatSync("只输出整理通顺的标准中文，不要任何多余描述：" + rawText);
                clean = (clean == null ? "" : clean).trim();
                result.put("text", clean.isBlank() ? rawText : clean);
                return result;
            } catch (Exception ignored) {
            }
        }
        result.put("text", rawText);
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
            double score = 0;
            try {
                score = Double.parseDouble(asrResult.getOrDefault("score", "0").toString());
            } catch (Exception ignored) {
            }

            if (rawText == null || rawText.isBlank()) {
                return Flux.just(sse("error", "没有听清您说的话，请靠近一点再说一遍。"));
            }

            // 2. 置信度低：大模型清洗方言口语
            String cleanText = rawText;
            if (score < 0.8) {
                try {
                    cleanText = llmUtil.chatSync("只输出整理通顺的标准中文，不要任何多余描述："
                            + rawText);
                    cleanText = (cleanText == null ? "" : cleanText).trim();
                    if (cleanText.isBlank()) cleanText = rawText;
                } catch (Exception e) {
                    cleanText = rawText;
                }
            }

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

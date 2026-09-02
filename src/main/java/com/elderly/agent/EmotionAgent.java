package com.elderly.agent;

import com.elderly.util.ChatCacheUtil;
import com.elderly.util.LlmUtil;
import com.elderly.util.PromptUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;

/**
 * Agent4：数字人情感陪伴智能体
 * 链路：情绪识别(human.txt) → 低落走安抚话术并推荐戏曲/老歌 → 正常闲聊（Redis缓存高频问答）。
 */
@Service
public class EmotionAgent {

    @Resource
    private LlmUtil llmUtil;
    @Resource
    private PromptUtil promptUtil;
    @Resource
    private ChatCacheUtil chatCacheUtil;

    /** 安抚话术系统提示词 */
    private static final String COMFORT_SYSTEM_PROMPT =
            "你是一位贴心的养老陪伴员，面前是一位情绪低落的老人。请用温暖、缓慢、简短的语气安抚他，"
                    + "先共情（如'我理解您心里不好受'），再给一句宽慰的话，最后主动提出陪他聊聊往事或听一段戏曲放松。回复不超过80字。";

    public AgentResult chatEmotion(String text) {
        // 1. 情绪识别
        String emotion = "正常";
        try {
            Map<String, String> param = new HashMap<>();
            param.put("text", text);
            String emotionPrompt = promptUtil.getPrompt("human.txt", param);
            String emotionRaw = llmUtil.chatSync(emotionPrompt);
            emotion = (emotionRaw == null ? "" : emotionRaw).trim();
        } catch (Exception e) {
            // 识别失败按正常处理，不影响对话
        }

        AgentResult result = AgentResult.of("chat", null);

        if (emotion.contains("低落")) {
            // 2a. 低落：安抚话术 + 推荐戏曲/老歌（前端展示播放按钮）
            result.setEmotion("低落");
            result.setStream(llmUtil.streamChat(COMFORT_SYSTEM_PROMPT, text));
            return result;
        }

        // 2b. 正常：高频问答先查Redis缓存（24小时），命中直接返回节省Token
        result.setEmotion("正常");
        String cached = chatCacheUtil.get(text);
        if (cached != null && !cached.isBlank()) {
            result.setStream(Flux.just(cached));
            return result;
        }

        // 未命中：流式生成，完成后写入缓存
        StringBuilder buffer = new StringBuilder();
        result.setStream(llmUtil.streamChat(text)
                .doOnNext(buffer::append)
                .doFinally(signal -> {
                    if (buffer.length() > 0) {
                        chatCacheUtil.put(text, buffer.toString());
                    }
                }));
        return result;
    }
}

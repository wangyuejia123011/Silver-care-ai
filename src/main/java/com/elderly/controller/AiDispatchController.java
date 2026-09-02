package com.elderly.controller;

import com.elderly.agent.VoiceAgent;
import com.elderly.common.R;
import com.elderly.dto.ChatRequest;
import jakarta.annotation.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * 统一AI调度中台入口
 * /api/ai/dispatch       语音统一入口（录音上传，SSE流式事件）
 * /api/ai/text/dispatch  文字统一入口（同一套路由，SSE流式事件）
 * /api/ai/tts            文字转语音（返回base64，前端写临时文件播放）
 */
@RestController
@RequestMapping("/api/ai")
public class AiDispatchController {

    @Resource
    private VoiceAgent voiceAgent;

    /**
     * 语音识别（两步式链路第一步）：录音文件 → 方言识别(+低置信度清洗) → 文本
     * 小程序端uploadFile不支持流式响应，故ASR单独走同步接口，文本再走SSE调度。
     */
    @PostMapping("/asr")
    public R<Map<String, Object>> asr(@RequestParam("audioFile") MultipartFile audioFile,
                                      @RequestParam(value = "dialect", defaultValue = "common") String dialect) {
        if (audioFile == null || audioFile.isEmpty()) {
            return R.fail(400, "录音文件为空");
        }
        try {
            return R.success("识别成功", voiceAgent.asrOnly(audioFile.getBytes(), dialect));
        } catch (Exception e) {
            return R.fail("语音识别失败：" + e.getMessage());
        }
    }

    /**
     * 小程序录音统一上传入口（SSE事件流）
     *
     * @param audioFile PCM录音文件（16k采样）
     * @param dialect    方言：sichuan/cantonese/henan/common
     * @param userId     老人用户ID（可选，用于档案补全与落库）
     */
    @PostMapping(value = "/dispatch", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> dispatch(
            @RequestParam("audioFile") MultipartFile audioFile,
            @RequestParam(value = "dialect", defaultValue = "common") String dialect,
            @RequestParam(value = "userId", required = false) Long userId
    ) {
        if (audioFile == null || audioFile.isEmpty()) {
            return Flux.just(errorEvent("录音文件为空"));
        }
        try {
            byte[] audioBytes = audioFile.getBytes();
            return voiceAgent.handleAudio(audioBytes, dialect, userId);
        } catch (Exception e) {
            return Flux.just(errorEvent("语音处理异常：" + e.getMessage()));
        }
    }

    /**
     * 文字统一调度入口（与语音共用意图路由，SSE事件流）
     */
    @PostMapping(value = "/text/dispatch", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> textDispatch(@RequestBody ChatRequest req) {
        if (req.getMessage() == null || req.getMessage().isBlank()) {
            return Flux.just(errorEvent("消息内容不能为空"));
        }
        return voiceAgent.handleText(req.getMessage(), req.getUserId());
    }

    /**
     * 健康管理语音/文字入口：跳过意图分类，固定走 HealthAgent
     */
    @PostMapping(value = "/health/dispatch", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> healthDispatch(@RequestBody ChatRequest req) {
        if (req.getMessage() == null || req.getMessage().isBlank()) {
            return Flux.just(errorEvent("健康描述不能为空"));
        }
        return voiceAgent.handleHealthText(req.getMessage(), req.getUserId());
    }

    /**
     * 文字转语音（TTS）：返回base64音频，前端写临时文件后播放
     */
    @PostMapping("/tts")
    public R<Map<String, String>> tts(@RequestParam String text) {
        if (text == null || text.isBlank()) {
            return R.fail(400, "文本内容不能为空");
        }
        try {
            byte[] audio = voiceAgent.generateVoiceAudio(text);
            if (audio == null || audio.length == 0) {
                return R.fail("语音合成失败：未返回音频");
            }
            Map<String, String> data = new HashMap<>();
            data.put("format", "mp3");
            data.put("audioBase64", Base64.getEncoder().encodeToString(audio));
            return R.success("语音合成成功", data);
        } catch (Exception e) {
            return R.fail("语音合成失败：" + e.getMessage());
        }
    }

    private ServerSentEvent<String> errorEvent(String msg) {
        return ServerSentEvent.<String>builder().event("error").data(msg).build();
    }
}

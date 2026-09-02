package com.elderly.controller;

import com.elderly.common.R;
import com.elderly.util.BaiduSpeechUtil;
import com.elderly.util.LlmUtil;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 服务健康检查接口
 * <p>
 * 提供两个功能：
 * 1. GET  /api/ai/health  —— 查看 AI 服务配置状态（不调用云服务，仅检查配置）
 * 2. POST /api/ai/test    —— 发送一条测试消息，验证 AI 对话是否正常
 * <p>
 * 用途：启动后端后，先访问此接口确认密钥配置正确，再使用正式 AI 对话。
 */
@RestController
@RequestMapping("/api/ai")
public class AiHealthController {

    @Resource
    private LlmUtil llmUtil;

    @Resource
    private BaiduSpeechUtil baiduSpeechUtil;

    /**
     * AI 服务配置状态检查（不调用云服务，仅检查配置是否就绪）。
     * <p>
     * 返回：
     * - aliyun.configured: true/false
     * - aliyun.model: 模型名称
     * - aliyun.keyPrefix: 密钥前4位（脱敏）
     * - baidu.configured: true/false
     * - baidu.appId: 百度AppID
     * - overall: "ready" / "partial" / "unavailable"
     */
    @GetMapping("/health")
    public R<Map<String, Object>> health() {
        Map<String, Object> result = new LinkedHashMap<>();

        // 阿里云通义千问状态
        Map<String, Object> aliyunStatus = llmUtil.healthCheck();
        result.put("aliyun", aliyunStatus);

        // 百度语音状态
        Map<String, Object> baiduStatus = baiduSpeechUtil.healthCheck();
        result.put("baidu", baiduStatus);

        // Chroma 向量库状态（仅检查配置，不实际连接）
        result.put("chroma", Map.of(
                "note", "Chroma向量库状态需通过实际对话验证",
                "collections", "elder_health_guide, elder_fraud_case"
        ));

        // 总体状态（如果配置看似就绪但实测失败，降级为 partial 并给出明确提示）
        boolean llmOk = llmUtil.isAiAvailable();
        boolean speechOk = baiduSpeechUtil.isConfigured();
        String testResult = aliyunStatus.get("testResult") != null
                ? String.valueOf(aliyunStatus.get("testResult")) : "skipped";
        if (llmOk && !("ok".equals(testResult) || "skipped".equals(testResult))) {
            llmOk = false;
        }

        String overall;
        String overallDesc;
        if (llmOk && speechOk) {
            overall = "ready";
            overallDesc = "全部AI服务已就绪";
        } else if (llmOk || speechOk) {
            overall = "partial";
            if (!llmOk) {
                overallDesc = "AI对话不可用：" + ("failed".equals(testResult)
                        ? "DashScope API Key 认证失败或网络异常，请检查密钥"
                        : "请在 application.properties 中配置 ai.aliyun.dashscope-api-key");
            } else {
                overallDesc = "部分AI服务可用（LLM: ok, 语音: missing）";
            }
        } else {
            overall = "unavailable";
            overallDesc = "AI服务未配置，请检查 application.properties";
        }
        result.put("overall", overall);
        result.put("overallDesc", overallDesc);

        return R.success("AI服务状态检查完成", result);
    }

    /**
     * AI 对话连通性测试（实际调用通义千问发送一条消息）。
     * <p>
     * 请求体：{ "message": "你好" }
     * 返回：{ "reply": "AI的回复内容" }
     */
    @PostMapping("/test")
    public R<Map<String, String>> testChat(@RequestBody Map<String, String> body) {
        String message = body != null ? body.getOrDefault("message", "你好") : "你好";
        if (message.isBlank()) {
            message = "你好";
        }

        if (!llmUtil.isAiAvailable()) {
            return R.fail("AI服务未配置：请在 application.properties 中设置 ai.aliyun.dashscope-api-key（sk-xxx 格式）");
        }

        String reply = llmUtil.chatSync(message);

        Map<String, String> data = new LinkedHashMap<>();
        data.put("message", message);
        data.put("reply", reply);
        data.put("model", "qwen-turbo");

        if (reply.isBlank() || reply.contains("暂未配置") || reply.contains("不可用")
                || reply.contains("请检查密钥") || reply.contains("认证失败")) {
            return R.fail("AI对话测试失败：未获得有效回复，请检查密钥配置");
        }

        return R.success("AI对话测试成功", data);
    }
}

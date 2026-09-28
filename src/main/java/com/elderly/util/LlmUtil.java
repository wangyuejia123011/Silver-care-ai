package com.elderly.util;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 大模型统一调用工具（生产级优化版）
 * <p>
 * 优先使用阿里云通义千问（OpenAI 兼容模式 HTTP 直连，支持全部新版模型），
 * 若未配置阿里云则降级到本地 Ollama（OpenAI 兼容接口）。
 * <p>
 * 优化要点：
 * 1. 阿里云走 compatible-mode/v1/chat/completions（OpenAI 兼容端点），
 *    新版模型（qwen3.x 等）不再依赖 DashScope 原生 Generation 端点，
 *    避免 "url error, please check url!" 400 错误
 * 2. 支持 DashScope API Key（sk-xxx）认证，兼容环境变量注入
 * 3. 复用 WebClient 单例，增加温度（temperature）、采样（topP）、最大 token 数控制
 * 4. Qwen3 系列支持通过 enable_thinking 关闭思考模式
 * 5. 启动时自检并输出配置状态日志，便于排查问题
 * 6. 提供 healthCheck() 方法供健康检查接口调用
 * 7. 错误处理不泄露密钥信息，用户友好提示
 */
@Component
public class LlmUtil {

    private static final Logger log = LoggerFactory.getLogger(LlmUtil.class);

    // ==================== 阿里云 DashScope 配置 ====================

    /** DashScope API Key（推荐，从百炼控制台获取，格式 sk-xxx） */
    @Value("${ai.aliyun.dashscope-api-key:}")
    private String dashscopeApiKey;

    /** 阿里云 AccessKey Secret（降级方案：当 dashscope-api-key 未配置时使用） */
    @Value("${ai.aliyun.access-key-secret:}")
    private String accessKeySecret;

    /** 阿里云模型名称 */
    @Value("${ai.aliyun.model:qwen-turbo}")
    private String aliModel;

    /** DashScope OpenAI 兼容端点基地址 */
    @Value("${ai.aliyun.base-url:https://dashscope.aliyuncs.com}")
    private String aliBaseUrl;

    /**
     * 生成温度（0~1，越高越随机，越低越确定）
     */
    @Value("${ai.aliyun.temperature:0.7}")
    private float temperature;

    /**
     * Top-P 采样（0~1，nucleus sampling，控制生成多样性）
     */
    @Value("${ai.aliyun.top-p:0.9}")
    private double topP;

    /** 最大生成 token 数 */
    @Value("${ai.aliyun.max-tokens:1024}")
    private int maxTokens;

    /**
     * 是否开启思考模式（仅 Qwen3 系列生效）。
     * 关闭后模型不再输出"思考过程"，回复更简洁、首字延迟更低，适合老人对话场景。
     */
    @Value("${ai.aliyun.enable-thinking:false}")
    private boolean enableThinking;

    // ==================== 本地 Ollama 配置 ====================

    @Value("${ai.local.base-url:}")
    private String ollamaUrl;

    @Value("${ai.local.model:}")
    private String ollamaModel;

    // ==================== 常量 ====================

    private static final Duration STREAM_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration SYNC_TIMEOUT = Duration.ofSeconds(30);

    private static final String DEFAULT_SYSTEM_PROMPT =
            "你是银龄智护AI助手，专门服务老年人。回答要通俗简短、语气温暖耐心，涉及健康和反诈问题要认真解答。";

    /**
     * AI 调用失败哨兵：流式调用异常时返回此标记，由上层翻译成友好提示或判定为失败。
     * 正常 AI 回复不会包含该字符串，因此可作为可靠的失败信号，避免把错误提示当成正文。
     */
    private static final String AI_FAIL_SENTINEL = "⟦AI_CALL_FAILED⟧";

    /** 流式场景下对老人展示的友好提示 */
    private static final String AI_FAIL_HINT = "（AI回复中断，请稍后重试）";

    // ==================== 运行时状态 ====================

    /** 阿里云 OpenAI 兼容端点客户端（复用单例，Authorization 头已内置） */
    private WebClient aliyunClient;

    /** 阿里云是否已配置可用 */
    private volatile boolean aliConfigured = false;

    /** Ollama 是否已配置可用 */
    private volatile boolean ollamaConfigured = false;

    /** 最近一次大模型调用异常信息（供健康检查/运维诊断，不泄露密钥） */
    private volatile String lastCallError;

    /** 实际使用的 API Key（启动时确定，避免每次调用重复读取配置） */
    private volatile String activeApiKey;

    /** Key 来源：用于启动日志诊断（application.properties / 环境变量 / 未配置） */
    private volatile String keySource = "未配置";

    /**
     * 启动时初始化：解析 API Key、创建 WebClient 实例、输出配置状态日志。
     */
    @PostConstruct
    public void init() {
        activeApiKey = resolveApiKey();

        if (activeApiKey != null && !activeApiKey.isBlank()) {
            try {
                aliyunClient = WebClient.builder()
                        .baseUrl(aliBaseUrl)
                        .defaultHeader("Authorization", "Bearer " + activeApiKey)
                        .build();
                aliConfigured = true;
                log.info("========================================");
                log.info(" 阿里云通义千问 AI 服务已启用（OpenAI 兼容模式）");
                log.info("   端点    : {}/compatible-mode/v1/chat/completions", aliBaseUrl);
                log.info("   模型    : {}", aliModel);
                log.info("   温度    : {}", temperature);
                log.info("   TopP    : {}", topP);
                log.info("   MaxTokens: {}", maxTokens);
                log.info("   思考模式 : {}", enableThinking ? "开启" : "关闭(默认)");
                log.info("   认证方式: DashScope API Key");
                log.info("   Key来源 : {}", keySource);
                log.info("   Key前缀 : {}***", maskKey(activeApiKey));
                log.info("========================================");
            } catch (Exception e) {
                log.error("阿里云通义千问初始化失败: {}", e.getMessage(), e);
                aliConfigured = false;
            }
        }

        if (!aliConfigured && accessKeySecret != null && !accessKeySecret.isBlank()) {
            log.warn("========================================");
            log.warn(" ⚠️ 检测到 ai.aliyun.access-key-secret 已配置，但缺少 ai.aliyun.dashscope-api-key");
            log.warn("    阿里云 AccessKey Secret 不能当作 DashScope API Key 使用，AI 对话仍会失败！");
            log.warn("    请从 https://bailian.console.aliyun.com/ 获取 DashScope API Key 并配置");
            log.warn("========================================");
        }

        if (!aliConfigured && ollamaUrl != null && !ollamaUrl.isBlank()) {
            ollamaConfigured = true;
            log.info("========================================");
            log.info(" 本地 Ollama AI 服务已启用");
            log.info("   地址: {}", ollamaUrl);
            log.info("   模型: {}", ollamaModel);
            log.info("========================================");
        }

        if (!aliConfigured && !ollamaConfigured) {
            log.warn("========================================");
            log.warn(" ⚠️ 未配置任何大模型，AI 对话功能将不可用！");
            log.warn("   配置方法（二选一）：");
            log.warn("   1. 阿里云: 在 application.properties 中设置");
            log.warn("      ai.aliyun.dashscope-api-key=sk-xxxxxxxx");
            log.warn("      （从 https://bailian.console.aliyun.com 获取）");
            log.warn("   2. 本地Ollama: 取消注释 ai.local.base-url 和 ai.local.model");
            log.warn("========================================");
        }
    }

    /**
     * 解析实际使用的 API Key。
     * 优先级：application.properties 的 ai.aliyun.dashscope-api-key > 环境变量 DASHSCOPE_API_KEY。
     * 注意：DashScope 通义千问只认百炼控制台发放的 API Key（sk-xxx 格式），
     * 阿里云 AccessKey Secret 不能当作 DashScope API Key 使用。
     */
    private String resolveApiKey() {
        if (dashscopeApiKey != null && !dashscopeApiKey.isBlank()) {
            keySource = "application.properties";
            return dashscopeApiKey.trim();
        }
        // 兼容 DashScope SDK 默认环境变量，便于在运行环境注入
        String envKey = System.getenv("DASHSCOPE_API_KEY");
        if (envKey != null && !envKey.isBlank()) {
            keySource = "环境变量 DASHSCOPE_API_KEY";
            return envKey.trim();
        }
        keySource = "未配置";
        return null;
    }

    /**
     * 对 API Key 做脱敏处理，仅显示前4位用于日志确认。
     */
    private String maskKey(String key) {
        if (key == null || key.length() <= 4) return "****";
        return key.substring(0, 4);
    }

    // ==================== 公共 API（保持向后兼容） ====================

    /**
     * 流式返回文本片段，供 SSE 逐字推送。
     *
     * @param prompt 用户输入
     * @return 文本片段流
     */
    public Flux<String> streamChat(String prompt) {
        return streamChat(DEFAULT_SYSTEM_PROMPT, prompt);
    }

    /**
     * 带系统提示词的流式对话。
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户输入
     * @return 文本片段流
     */
    public Flux<String> streamChat(String systemPrompt, String userPrompt) {
        // 流式场景：把失败哨兵翻译成老人能看懂的提示
        return rawStream(systemPrompt, userPrompt)
                .map(s -> AI_FAIL_SENTINEL.equals(s) ? AI_FAIL_HINT : s);
    }

    /**
     * 原始流式调用（不翻译失败哨兵），供 chatSync 在 AI 失败时返回 null 以触发降级。
     */
    private Flux<String> rawStream(String systemPrompt, String userPrompt) {
        String safeSysPrompt = resolveSystemPrompt(systemPrompt);
        if (aliConfigured) {
            return streamAliQwen(safeSysPrompt, userPrompt);
        }
        if (ollamaConfigured) {
            return streamOllama(safeSysPrompt, userPrompt);
        }
        log.warn("未配置任何大模型（阿里云/Ollama），返回失败哨兵");
        return Flux.just(AI_FAIL_SENTINEL);
    }

    /**
     * 同步调用（阻塞等待完整结果），适用于需要完整文本再做二次判断的场景。
     * 增加超时控制，防止 .block() 无限等待导致线程池耗尽。
     * 若 AI 不可用或调用失败，返回 null（由调用方决定降级策略）。
     */
    public String chatSync(String prompt) {
        return chatSync(DEFAULT_SYSTEM_PROMPT, prompt);
    }

    /**
     * 同步调用（带系统提示词），阻塞等待完整结果。
     * AI 调用失败时返回 null，便于上层走 fallback / 模板兜底。
     */
    public String chatSync(String systemPrompt, String userPrompt) {
        try {
            String result = rawStream(systemPrompt, userPrompt)
                    .reduce("", String::concat)
                    .block(SYNC_TIMEOUT);
            // 失败哨兵或空结果一律视为调用失败
            if (result == null || result.equals(AI_FAIL_SENTINEL) || result.isBlank()) {
                return null;
            }
            return result;
        } catch (Exception e) {
            log.warn("LLM 同步调用异常（将返回 null 走降级）: {}", e.getMessage(), e);
            return null;
        }
    }

    // ==================== 阿里云通义千问流式调用 ====================

    /**
     * 阿里云通义千问流式调用 —— 直连 OpenAI 兼容端点（compatible-mode/v1/chat/completions）。
     * <p>
     * 背景：DashScope 原生 Generation SDK 端点不支持 qwen3.x 等新版模型，
     * 调用会报 400 InvalidParameter "url error, please check url!"。
     * OpenAI 兼容端点覆盖全部在售模型，且 enable_thinking 等扩展参数直接放 body 即可。
     */
    private Flux<String> streamAliQwen(String systemPrompt, String userPrompt) {
        return Flux.defer(() -> {
            com.alibaba.fastjson2.JSONObject body = new com.alibaba.fastjson2.JSONObject();
            body.put("model", aliModel);
            body.put("stream", true);

            com.alibaba.fastjson2.JSONArray messages = new com.alibaba.fastjson2.JSONArray();
            com.alibaba.fastjson2.JSONObject sysMsg = new com.alibaba.fastjson2.JSONObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);
            com.alibaba.fastjson2.JSONObject userMsg = new com.alibaba.fastjson2.JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", userPrompt);
            messages.add(sysMsg);
            messages.add(userMsg);
            body.put("messages", messages);

            body.put("temperature", temperature);
            body.put("top_p", topP);
            if (maxTokens > 0) {
                body.put("max_tokens", maxTokens);
            }

            // Qwen3 系列思考模式控制：默认关闭，避免老人看到"思考过程"。
            // 兼容模式下 enable_thinking 直接作为 body 顶层字段传递。
            if (!enableThinking && aliModel != null && aliModel.startsWith("qwen3")) {
                body.put("enable_thinking", false);
            }

            return aliyunClient.post()
                    .uri("/compatible-mode/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body.toJSONString())
                    .retrieve()
                    .bodyToFlux(String.class)
                    .timeout(STREAM_TIMEOUT)
                    .map(this::extractDeltaContent)
                    .filter(s -> !s.isEmpty())
                    .onErrorResume(e -> {
                        String msg = describeError(e);
                        log.error("千问流式调用异常: {}", msg, e);
                        lastCallError = msg;
                        return Flux.just(AI_FAIL_SENTINEL);
                    });
        });
    }

    /**
     * 解析 OpenAI 兼容端点的 SSE 数据行，提取增量文本。
     * 行格式："data: {...}" 或 "data: [DONE]"；对错误响应（含 error 字段）记录真实原因。
     */
    private String extractDeltaContent(String rawLine) {
        String line = rawLine == null ? "" : rawLine.trim();
        if (line.isEmpty() || "[DONE]".equals(line)) {
            return "";
        }
        // SSE 前缀剥离（兼容 "data:" 紧贴与 "data: " 带空格两种格式）
        if (line.startsWith("data:")) {
            line = line.substring(5).trim();
        }
        if (line.isEmpty() || "[DONE]".equals(line)) {
            return "";
        }
        try {
            com.alibaba.fastjson2.JSONObject json = com.alibaba.fastjson2.JSON.parseObject(line);
            if (json == null) {
                return "";
            }
            // 错误响应：{"error":{"code":"...","message":"..."}}
            com.alibaba.fastjson2.JSONObject error = json.getJSONObject("error");
            if (error != null) {
                String err = error.getString("code") + ": " + error.getString("message");
                log.error("千问兼容端点返回错误: {}", err);
                lastCallError = err;
                return "";
            }
            com.alibaba.fastjson2.JSONArray choices = json.getJSONArray("choices");
            if (choices != null && !choices.isEmpty()) {
                com.alibaba.fastjson2.JSONObject delta = choices.getJSONObject(0).getJSONObject("delta");
                if (delta != null) {
                    String content = delta.getString("content");
                    return content != null ? content : "";
                }
            }
            return "";
        } catch (Exception e) {
            log.debug("跳过无法解析的千问SSE行: {}", line);
            return "";
        }
    }

    /**
     * 把调用异常转成简明的错误描述（HTTP 状态 + 响应体片段），不含密钥。
     */
    private String describeError(Throwable e) {
        if (e instanceof WebClientResponseException wcre) {
            String body = wcre.getResponseBodyAsString();
            if (body != null && body.length() > 300) {
                body = body.substring(0, 300) + "…";
            }
            return "HTTP " + wcre.getStatusCode().value() + " " + wcre.getStatusText()
                    + (body != null && !body.isBlank() ? " body=" + body : "");
        }
        String msg = e.getMessage();
        return msg != null ? msg : e.getClass().getSimpleName();
    }

    // ==================== 本地 Ollama 流式调用 ====================

    /**
     * 本地 Ollama 流式调用 —— 通过 OpenAI 兼容接口的 SSE 流。
     * 优化：使用 fastjson2 构建请求体，避免手动拼接 JSON 的脆弱性。
     */
    private Flux<String> streamOllama(String systemPrompt, String userPrompt) {
        WebClient client = WebClient.builder()
                .baseUrl(ollamaUrl)
                .build();

        // 使用 fastjson2 构建请求体，避免手动转义
        com.alibaba.fastjson2.JSONObject body = new com.alibaba.fastjson2.JSONObject();
        body.put("model", ollamaModel);
        body.put("stream", true);
        com.alibaba.fastjson2.JSONArray messages = new com.alibaba.fastjson2.JSONArray();
        com.alibaba.fastjson2.JSONObject sysMsg = new com.alibaba.fastjson2.JSONObject();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemPrompt);
        com.alibaba.fastjson2.JSONObject userMsg = new com.alibaba.fastjson2.JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);
        messages.add(sysMsg);
        messages.add(userMsg);
        body.put("messages", messages);

        return client.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body.toJSONString())
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(STREAM_TIMEOUT)
                .filter(line -> !line.isBlank() && !"[DONE]".equals(line.trim()))
                .map(line -> {
                    try {
                        com.alibaba.fastjson2.JSONObject json = com.alibaba.fastjson2.JSON.parseObject(line);
                        com.alibaba.fastjson2.JSONArray choices = json.getJSONArray("choices");
                        if (choices != null && !choices.isEmpty()) {
                            com.alibaba.fastjson2.JSONObject delta = choices.getJSONObject(0).getJSONObject("delta");
                            if (delta != null) {
                                String content = delta.getString("content");
                                return content != null ? content : "";
                            }
                        }
                        return "";
                    } catch (Exception e) {
                        log.debug("跳过无法解析的Ollama行: {}", line);
                        return "";
                    }
                })
                .filter(s -> !s.isEmpty())
                .onErrorResume(e -> {
                    log.error("Ollama流式调用异常: {}", e.getMessage(), e);
                    return Flux.just(AI_FAIL_SENTINEL);
                });
    }

    // ==================== 健康检查 ====================

    /**
     * 健康检查：返回当前 AI 服务配置状态，供运维接口调用。
     * 不泄露密钥，仅显示脱敏信息。
     */
    public Map<String, Object> healthCheck() {
        Map<String, Object> status = new LinkedHashMap<>();

        // 阿里云状态
        Map<String, Object> aliyun = new LinkedHashMap<>();
        aliyun.put("configured", aliConfigured);
        aliyun.put("model", aliModel);
        aliyun.put("endpoint", aliBaseUrl + "/compatible-mode/v1/chat/completions");
        aliyun.put("temperature", temperature);
        aliyun.put("topP", topP);
        aliyun.put("maxTokens", maxTokens);
        aliyun.put("enableThinking", enableThinking);
        if (aliConfigured) {
            aliyun.put("authMethod", "DashScope API Key");
            aliyun.put("keyPrefix", maskKey(activeApiKey) + "***");
        } else if (accessKeySecret != null && !accessKeySecret.isBlank()) {
            aliyun.put("warning", "已配置 AccessKey Secret，但缺少 DashScope API Key，AI 无法使用");
        }
        status.put("aliyun", aliyun);

        // Ollama 状态
        Map<String, Object> ollama = new LinkedHashMap<>();
        ollama.put("configured", ollamaConfigured);
        if (ollamaConfigured) {
            ollama.put("url", ollamaUrl);
            ollama.put("model", ollamaModel);
        }
        status.put("ollama", ollama);

        // 实时测试
        status.put("activeProvider", aliConfigured ? "aliyun" : (ollamaConfigured ? "ollama" : "none"));

        if (aliConfigured || ollamaConfigured) {
            lastCallError = null;
            try {
                String testReply = chatSync("你好");
                boolean ok = testReply != null && !testReply.isBlank()
                        && !testReply.contains("暂未配置") && !testReply.contains("不可用");
                status.put("testResult", ok ? "ok" : "empty");
                status.put("testReply", testReply != null && testReply.length() > 100
                        ? testReply.substring(0, 100) + "…" : testReply);
                if (!ok && lastCallError != null) {
                    status.put("lastError", lastCallError);
                }
            } catch (Exception e) {
                status.put("testResult", "failed");
                status.put("testError", e.getMessage());
                status.put("lastError", e.getMessage());
            }
        } else {
            status.put("testResult", "skipped");
        }

        return status;
    }

    /**
     * 百度语音服务状态（委托 BaiduSpeechUtil，此处仅返回占位）。
     * 实际语音健康检查由 BaiduSpeechUtil.healthCheck() 提供。
     */
    public boolean isAiAvailable() {
        return aliConfigured || ollamaConfigured;
    }

    /** 供健康检查/运维读取最近一次调用异常（不含密钥） */
    public String getLastCallError() {
        return lastCallError;
    }

    // ==================== 工具方法 ====================

    /**
     * 系统提示词兜底：如果传入为空则使用默认提示词。
     */
    private String resolveSystemPrompt(String prompt) {
        return (prompt == null || prompt.isBlank()) ? DEFAULT_SYSTEM_PROMPT : prompt;
    }
}

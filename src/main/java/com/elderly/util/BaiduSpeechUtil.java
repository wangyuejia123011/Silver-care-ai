package com.elderly.util;

import com.baidu.aip.speech.AipSpeech;
import com.baidu.aip.speech.TtsResponse;
import jakarta.annotation.PostConstruct;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 百度语音 SDK 工具类（生产级优化版）
 * <p>
 * 提供：
 * 1. 多方言 ASR 语音识别（普通话/四川话/粤语/河南话）
 * 2. TTS 文字转语音合成
 * <p>
 * 优化要点：
 * 1. 复用 AipSpeech 单例，避免每次调用创建新实例
 * 2. ASR 返回结果增加错误码检查，不再静默吞异常
 * 3. TTS 增加超时配置和空响应检查
 * 4. 启动时输出配置状态日志
 * 5. 提供 healthCheck() 方法供健康检查接口调用
 */
@Component
public class BaiduSpeechUtil {

    private static final Logger log = LoggerFactory.getLogger(BaiduSpeechUtil.class);

    @Value("${baidu.appId:}")
    private String appId;

    @Value("${baidu.apiKey:}")
    private String apiKey;

    @Value("${baidu.secretKey:}")
    private String secretKey;

    /** 复用的 AipSpeech 客户端实例（TTS 使用） */
    private AipSpeech client;

    /** 是否已配置可用 */
    private volatile boolean configured = false;

    /** 百度 access_token（REST API 直连 ASR 用，带缓存） */
    private volatile String accessToken;
    private volatile long tokenExpireTime = 0;

    /**
     * 启动时初始化百度语音客户端。
     */
    @PostConstruct
    public void init() {
        if (appId != null && !appId.isBlank()
                && apiKey != null && !apiKey.isBlank()
                && secretKey != null && !secretKey.isBlank()) {
            try {
                client = new AipSpeech(appId, apiKey, secretKey);
                // 网络超时配置
                client.setConnectionTimeoutInMillis(5000);   // 连接超时 5s
                client.setSocketTimeoutInMillis(30000);        // 读取超时 30s（TTS 可能较慢）
                configured = true;
                log.info("========================================");
                log.info(" 百度语音服务已启用");
                log.info("   AppID  : {}", appId);
                log.info("   APIKey : {}***", maskKey(apiKey));
                log.info("   超时   : 连接5s / 读取30s");
                log.info("========================================");
            } catch (Exception e) {
                log.error("百度语音客户端初始化失败: {}", e.getMessage(), e);
            }
        } else {
            log.warn("========================================");
            log.warn(" ⚠️ 百度语音服务未配置，语音识别/合成功能将不可用！");
            log.warn("   配置方法：在 application.properties 中设置");
            log.warn("   baidu.appId=你的AppID");
            log.warn("   baidu.apiKey=你的APIKey");
            log.warn("   baidu.secretKey=你的SecretKey");
            log.warn("   （从 https://console.bce.baidu.com/ai 获取）");
            log.warn("========================================");
        }
    }

    /**
     * 多方言 ASR 识别。
     * <p>
     * dev_pid 方言模型映射（百度官方标准，必须为 int 类型）：
     * 1537 普通话(输入法模型) | 1637 粤语 | 1837 四川话 | 1737 英语
     * 河南话百度无独立方言模型，回退到普通话模型(1537)识别效果最佳
     * <p>
     * 容错：当方言模式识别失败（多为方言模型未开通）时，自动用普通话模型兜底重试一次，
     * 保证功能不中断，并在日志中提示真实原因。
     *
     * @param audioBytes PCM 音频数据（16kHz 单声道）
     * @param dialect    方言类型：common/sichuan/cantonese/henan
     * @return 识别结果 Map（包含 text、score、err_no 等字段）
     */
    public HashMap<String, Object> asr(byte[] audioBytes, String dialect) {
        HashMap<String, Object> failResult = new HashMap<>();
        failResult.put("text", "");
        failResult.put("score", 0);

        if (!configured || client == null) {
            log.warn("百度语音未配置，ASR不可用");
            failResult.put("err_no", -1);
            failResult.put("err_msg", "百度语音服务未配置");
            return failResult;
        }

        if (audioBytes == null || audioBytes.length == 0) {
            log.warn("ASR音频数据为空");
            failResult.put("err_no", -2);
            failResult.put("err_msg", "音频数据为空");
            return failResult;
        }

        int commonPid = 1537;
        int targetPid = switch (dialect) {
            case "sichuan" -> 1837;      // 四川话
            case "cantonese" -> 1637;    // 粤语
            case "henan" -> 1537;        // 河南话：无专用模型，回退普通话
            default -> 1537;             // 普通话
        };
        String label = dialectLabel(dialect);

        // 优先直连百度短语音识别 REST API（自己透传 dev_pid，确保方言模型真正生效，
        // 不受 AIP SDK 封装对 dev_pid 的潜在忽略影响）
        try {
            if (!"common".equals(dialect)) {
                log.info("百度ASR方言识别[直连]: dialect={}, dev_pid={} ({}), audioLen={}",
                        dialect, targetPid, label, audioBytes.length);
            }
            JSONObject jsonResult = asrByRestApi(audioBytes, targetPid);
            int errNo = jsonResult.optInt("err_no", -999);

            // 方言模型失败时（多为方言模型未开通或音频不匹配），用普通话模型兜底重试一次
            if (errNo != 0 && !"common".equals(dialect)) {
                String firstErr = jsonResult.optString("err_msg", "未知错误");
                log.warn("百度ASR方言模型({}/{})直连失败 err_no={} ({})，改用普通话模型兜底",
                        dialect, targetPid, errNo, firstErr);
                jsonResult = asrByRestApi(audioBytes, commonPid);
                errNo = jsonResult.optInt("err_no", -999);
            }

            if (errNo != 0) {
                String errMsg = jsonResult.optString("err_msg", "未知错误");
                log.error("百度ASR识别失败: err_no={}, err_msg={}", errNo, errMsg);
                failResult.put("err_no", errNo);
                failResult.put("err_msg", errMsg);
                return failResult;
            }

            return parseAsrResult(jsonResult, dialect);

        } catch (Exception e) {
            // REST 直连异常时回退到 AIP SDK 的 asr 方法作为最后兜底
            log.warn("百度ASR REST直连异常({})，回退SDK", e.getMessage());
            try {
                HashMap<String, Object> options = new HashMap<>();
                options.put("dev_pid", targetPid);
                JSONObject sdkResult = client.asr(audioBytes, "pcm", 16000, options);
                int errNo = sdkResult.optInt("err_no", -999);
                if (errNo != 0) {
                    failResult.put("err_no", errNo);
                    failResult.put("err_msg", sdkResult.optString("err_msg", "未知错误"));
                    return failResult;
                }
                return parseAsrResult(sdkResult, dialect);
            } catch (Exception e2) {
                log.error("百度ASR调用异常: {}", e2.getMessage(), e2);
                failResult.put("err_no", -3);
                failResult.put("err_msg", "ASR调用异常: " + e2.getMessage());
                return failResult;
            }
        }
    }

    /**
     * 直连百度短语音识别 REST API（server_api），自己透传 dev_pid，确保方言模型生效。
     */
    private JSONObject asrByRestApi(byte[] audio, int devPid) throws Exception {
        String token = getAccessToken();
        if (token == null) {
            JSONObject fail = new JSONObject();
            fail.put("err_no", -10);
            fail.put("err_msg", "获取百度access_token失败");
            return fail;
        }
        String base64Audio = Base64.getEncoder().encodeToString(audio);
        JSONObject body = new JSONObject();
        body.put("format", "pcm");
        body.put("rate", 16000);
        body.put("channel", 1);
        body.put("cuid", "silver-care-ai");
        body.put("token", token);
        body.put("dev_pid", devPid);
        body.put("speech", base64Audio);
        body.put("len", audio.length);

        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://vop.baidu.com/server_api"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return new JSONObject(resp.body());
    }

    /**
     * 获取百度 access_token（带缓存，有效期约30天，缓存25天）。
     */
    private String getAccessToken() throws Exception {
        long now = System.currentTimeMillis();
        if (accessToken != null && now < tokenExpireTime) {
            return accessToken;
        }
        String url = "https://aip.baidubce.com/oauth/2.0/token?grant_type=client_credentials"
                + "&client_id=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(secretKey, StandardCharsets.UTF_8);
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JSONObject json = new JSONObject(resp.body());
        String token = json.optString("access_token", "");
        if (token.isEmpty()) {
            log.error("获取百度access_token失败: {}", resp.body());
            return null;
        }
        accessToken = token;
        tokenExpireTime = now + 25L * 24 * 60 * 60 * 1000; // 缓存25天
        log.info("百度access_token获取成功（已缓存25天）");
        return accessToken;
    }

    /** 统一解析百度ASR返回（兼容直连与SDK两种结构），提取文本与 score */
    private HashMap<String, Object> parseAsrResult(JSONObject jsonResult, String dialect) {
        HashMap<String, Object> result = new HashMap<>(jsonResult.toMap());
        String recognized = extractResultText(jsonResult);
        if (!recognized.isEmpty()) {
            result.put("text", recognized);
        }
        if (jsonResult.has("score")) {
            result.put("score", jsonResult.optDouble("score", 0));
        }
        log.info("百度ASR识别成功: dialect={}, text={}, hasScore={}",
                dialect,
                recognized.length() > 50 ? recognized.substring(0, 50) + "…" : recognized,
                jsonResult.has("score"));
        return result;
    }

    /** 方言中文标签，仅用于日志 */
    private String dialectLabel(String dialect) {
        return switch (dialect) {
            case "sichuan" -> "四川话";
            case "cantonese" -> "粤语";
            case "henan" -> "河南话";
            default -> "普通话";
        };
    }

    /**
     * 从百度 ASR 返回值中提取识别文本。
     * 百度 AIP Java SDK 的 asr() 成功返回结构为 {"err_no":0,"result":["识别文本"],...}，
     * 文本位于 result 数组，不存在 text 字段；部分封装版本可能直接返回 text 字段，这里做兼容。
     */
    private String extractResultText(JSONObject json) {
        if (json.has("result")) {
            try {
                org.json.JSONArray arr = json.getJSONArray("result");
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < arr.length(); i++) {
                    sb.append(arr.getString(i));
                }
                return sb.toString().trim();
            } catch (Exception ignored) {
            }
        }
        return json.optString("text", "").trim();
    }

    /**
     * TTS 文字转音频字节。
     *
     * @param text 待合成的文本（建议不超过 500 字）
     * @return MP3 音频字节数组，失败时返回 null
     */
    public byte[] tts(String text) {
        if (!configured || client == null) {
            log.warn("百度语音未配置，TTS不可用");
            return null;
        }

        if (text == null || text.isBlank()) {
            log.warn("TTS文本为空，跳过合成");
            return null;
        }

        // 文本过长时截断（百度TTS单次建议不超过 500 字）
        String safeText = text.length() > 500 ? text.substring(0, 500) : text;

        try {
            TtsResponse response = client.synthesis(safeText, "zh", 4, null);
            byte[] audioData = response.getData();

            if (audioData == null || audioData.length == 0) {
                // TTS 失败时百度 SDK 会返回错误信息而非音频数据
                JSONObject error = response.getResult();
                String errMsg = error != null ? error.optString("err_msg", "未知错误") : "无错误信息";
                log.error("百度TTS合成失败: {}", errMsg);
                return null;
            }

            log.debug("百度TTS合成成功: 文本长度={}, 音频大小={}bytes",
                    safeText.length(), audioData.length);
            return audioData;

        } catch (Exception e) {
            log.error("百度TTS调用异常: {}", e.getMessage(), e);
            return null;
        }
    }

    // ==================== 健康检查 ====================

    /**
     * 健康检查：返回百度语音服务配置状态。
     */
    public Map<String, Object> healthCheck() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("configured", configured);
        if (configured) {
            status.put("appId", appId);
            status.put("apiKey", maskKey(apiKey) + "***");
            status.put("supportedDialects", new String[]{"普通话", "四川话", "粤语", "河南话"});
            status.put("timeout", Map.of("connect", "5s", "socket", "30s"));
        }
        return status;
    }

    public boolean isConfigured() {
        return configured;
    }

    // ==================== 工具方法 ====================

    private String maskKey(String key) {
        if (key == null || key.length() <= 4) return "****";
        return key.substring(0, 4);
    }
}

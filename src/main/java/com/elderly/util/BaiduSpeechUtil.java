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
                boolean sdkWav = isWav(audioBytes);
                byte[] sdkAudio = sdkWav ? resampleWavTo16k(audioBytes) : audioBytes;
                JSONObject sdkResult = client.asr(sdkAudio, sdkWav ? "wav" : "pcm", 16000, options);
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
        // 自适应格式：wav 自带文件头(自描述采样率/位深)，从文件头读取真实采样率传给百度，
        // 彻底规避真机 RecorderManager 忽略 sampleRate、rate 与音频实际不符导致的整句识别失败；
        // pcm 为裸数据，沿用 16000
        boolean wav = isWav(audio);
        if (wav) {
            // 真机 wav 实际采样率常被 RecorderManager 忽略（如按 44100 录），
            // 百度 REST API 的 rate 仅支持 8k/16k，裸透传非标采样率会直接识别失败。
            // 统一重采样到 16k，彻底规避采样率不匹配导致的整句听不懂。
            audio = resampleWavTo16k(audio);
        }
        body.put("format", wav ? "wav" : "pcm");
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
     * 判断音频是否为 WAV 格式（RIFF....WAVE 文件头）。
     * 微信 RecorderManager 用 format:'wav' 录制的即为标准 WAV，自带采样率/位深，百度可免填 rate 直接解析。
     */
    private boolean isWav(byte[] audio) {
        if (audio == null || audio.length < 12) return false;
        // "RIFF" at 0, "WAVE" at 8
        return audio[0] == 'R' && audio[1] == 'I' && audio[2] == 'F' && audio[3] == 'F'
                && audio[8] == 'W' && audio[9] == 'A' && audio[10] == 'V' && audio[11] == 'E';
    }

    /**
     * 从 WAV 文件头读取真实采样率（offset 24 起 4 字节 little-endian），
     * 避免依赖调用方声称的 sampleRate（真机 RecorderManager 常忽略该参数）。
     * 非 WAV 或头部不完整时回退 16000。
     */
    private int readWavSampleRate(byte[] audio) {
        if (audio == null || audio.length < 28) return 16000;
        int rate = (audio[24] & 0xff)
                | ((audio[25] & 0xff) << 8)
                | ((audio[26] & 0xff) << 16)
                | ((audio[27] & 0xff) << 24);
        return (rate > 0) ? rate : 16000;
    }

    /**
     * 将 WAV 重采样到 16kHz/16bit（线性插值）。
     * 真机录出的 wav 采样率可能是 44100/32000/48000 等百度不支持的值，
     * 统一降到 16k 后再发给百度，确保 rate=16000 一定匹配。
     * 非 WAV、已是 16k、或非 16bit 的情况原样返回（交给百度按原逻辑处理）。
     */
    private byte[] resampleWavTo16k(byte[] wav) {
        if (!isWav(wav) || wav.length < 44) return wav;
        int channels = readShortLE(wav, 22);
        int bitsPerSample = readShortLE(wav, 34);
        int sampleRate = readIntLE(wav, 24);
        if (channels <= 0 || bitsPerSample != 16 || sampleRate == 16000) {
            return wav;
        }
        int dataPos = findSubchunk(wav, "data");
        if (dataPos < 0) return wav;
        int dataSize = readIntLE(wav, dataPos + 4);
        int dataStart = dataPos + 8;
        if (dataStart + dataSize > wav.length) dataSize = wav.length - dataStart;
        if (dataSize <= 0) return wav;

        int bytesPerSample = bitsPerSample / 8;
        int frameSize = channels * bytesPerSample;
        int origFrames = dataSize / frameSize;
        double ratio = (double) 16000 / sampleRate;
        int newFrames = (int) Math.round(origFrames * ratio);
        if (newFrames <= 0) return wav;

        byte[] newData = new byte[newFrames * frameSize];
        for (int c = 0; c < channels; c++) {
            for (int i = 0; i < newFrames; i++) {
                double srcPos = i / ratio;            // 源帧浮点位置
                int i0 = (int) srcPos;
                int i1 = Math.min(i0 + 1, origFrames - 1);
                double frac = srcPos - i0;
                int s0 = readShortLE(wav, dataStart + i0 * frameSize + c * bytesPerSample);
                int s1 = readShortLE(wav, dataStart + i1 * frameSize + c * bytesPerSample);
                int val = (int) (s0 + (s1 - s0) * frac);
                if (val > 32767) val = 32767;
                if (val < -32768) val = -32768;
                writeShortLE(newData, i * frameSize + c * bytesPerSample, val);
            }
        }

        // 重建标准 44 字节 PCM WAV 头（16k）
        int newDataSize = newData.length;
        byte[] out = new byte[44 + newDataSize];
        out[0] = 'R'; out[1] = 'I'; out[2] = 'F'; out[3] = 'F';
        writeIntLE(out, 4, 36 + newDataSize);
        out[8] = 'W'; out[9] = 'A'; out[10] = 'V'; out[11] = 'E';
        out[12] = 'f'; out[13] = 'm'; out[14] = 't'; out[15] = ' ';
        writeIntLE(out, 16, 16);                       // subchunk1Size
        writeShortLE(out, 20, 1);                     // PCM
        writeShortLE(out, 22, channels);
        writeIntLE(out, 24, 16000);                   // 采样率固定 16k
        writeIntLE(out, 28, 16000 * channels * bytesPerSample); // byteRate
        writeShortLE(out, 32, (short) (channels * bytesPerSample)); // blockAlign
        writeShortLE(out, 34, (short) bitsPerSample);
        out[36] = 'd'; out[37] = 'a'; out[38] = 't'; out[39] = 'a';
        writeIntLE(out, 40, newDataSize);
        System.arraycopy(newData, 0, out, 44, newDataSize);
        return out;
    }

    // ---- WAV 解析辅助 ----
    private int readShortLE(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }
    private void writeShortLE(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xff);
        b[off + 1] = (byte) ((v >> 8) & 0xff);
    }
    private int readIntLE(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }
    private void writeIntLE(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xff);
        b[off + 1] = (byte) ((v >> 8) & 0xff);
        b[off + 2] = (byte) ((v >> 16) & 0xff);
        b[off + 3] = (byte) ((v >> 24) & 0xff);
    }
    /** 在 WAV 中查找名为 id 的子块偏移（'data' 等），找不到返回 -1 */
    private int findSubchunk(byte[] wav, String id) {
        int p = 12;
        while (p + 8 <= wav.length) {
            String tag = new String(wav, p, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int size = readIntLE(wav, p + 4);
            if (tag.equals(id)) return p;
            p += 8 + size + (size & 1); // 按 2 字节对齐跳过
        }
        return -1;
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

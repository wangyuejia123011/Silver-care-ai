package com.elderly.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.elderly.entity.WxSubscribeRecord;
import com.elderly.mapper.WxSubscribeRecordMapper;
import com.elderly.service.WxSubscribeService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 微信小程序订阅消息服务实现。
 *
 * 说明：
 * 1. access_token 会缓存 90 分钟（微信有效期 7200 秒），避免每次发送都请求微信接口。
 * 2. 发送失败时只记录日志，不影响主业务流程。
 * 3. 需要在 application.properties 中配置 wx.miniapp.app-id、app-secret 和模板 ID。
 */
@Service
public class WxSubscribeServiceImpl implements WxSubscribeService {

    private static final Logger log = LoggerFactory.getLogger(WxSubscribeServiceImpl.class);

    @Value("${wx.miniapp.app-id:}")
    private String appId;

    @Value("${wx.miniapp.app-secret:}")
    private String appSecret;

    @Value("${wx.miniapp.template.fraud-risk:}")
    private String defaultFraudRiskTemplateId;

    @Value("${wx.miniapp.template.health-abnormal:}")
    private String defaultHealthAbnormalTemplateId;

    /** 小程序版本状态：formal=正式版，trial=体验版，developer=开发版（体验版测试时设为 trial） */
    @Value("${wx.miniapp.miniprogram-state:formal}")
    private String miniprogramState;

    /**
     * 微信开放接口域名。
     * 默认 https 公网地址；部署在微信云托管时必须改为 http://api.weixin.qq.com（内网直连），
     * 否则 https 证书在云托管内网网关下校验失败（PKIX path building failed）。
     */
    @Value("${wx.api-base-url:https://api.weixin.qq.com}")
    private String wxApiBaseUrl;

    @Resource
    private WxSubscribeRecordMapper wxSubscribeRecordMapper;

    private final RestTemplate restTemplate = new RestTemplate();

    /** access_token 缓存 */
    private volatile String accessToken;
    private volatile long accessTokenExpireAt = 0;

    /** 缓存锁 */
    private final Object tokenLock = new Object();

    @Override
    public WxSubscribeRecord recordAuth(String openId, Long bindId, String templateId, String authType) {
        if (openId == null || openId.isBlank() || templateId == null || templateId.isBlank()) {
            throw new IllegalArgumentException("openId 和 templateId 不能为空");
        }
        if (authType == null || (!"permanent".equals(authType) && !"once".equals(authType))) {
            authType = "once";
        }

        WxSubscribeRecord record = new WxSubscribeRecord();
        record.setOpenId(openId);
        record.setBindId(bindId);
        record.setTemplateId(templateId);
        record.setAuthType(authType);
        record.setRemainCount("permanent".equals(authType) ? 999999 : 1);
        record.setAuthTime(LocalDateTime.now());
        record.setSendStatus(null);
        record.setFailReason(null);
        wxSubscribeRecordMapper.insert(record);
        log.info("订阅消息授权已记录: openId={}, templateId={}, authType={}", openId, templateId, authType);
        return record;
    }

    @Override
    public Map<String, Object> sendSubscribeMessage(String openId, String templateId, String page,
                                                    Map<String, Map<String, String>> data) {
        return doSend(openId, templateId, page, data, false);
    }

    @Override
    public Map<String, Object> sendSubscribeMessageRaw(String openId, String templateId, String page,
                                                       Map<String, Map<String, String>> data) {
        return doSend(openId, templateId, page, data, true);
    }

    private Map<String, Object> doSend(String openId, String templateId, String page,
                                       Map<String, Map<String, String>> data, boolean skipAuthCheck) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);

        if (templateId == null || templateId.isBlank()) {
            result.put("errMsg", "未指定微信订阅消息模板 ID");
            return result;
        }
        if (openId == null || openId.isBlank()) {
            result.put("errMsg", "接收者 openId 为空");
            return result;
        }
        if (!skipAuthCheck && !hasAvailableAuth(openId, templateId)) {
            result.put("errMsg", "用户未授权该订阅消息模板（hasAvailableAuth=false，推送在授权检查处被拦截，未真正调用微信）");
            return result;
        }

        String token = getAccessToken();
        if (token == null) {
            result.put("errMsg", "获取微信 access_token 失败，请检查 wx.miniapp.app-id/app-secret");
            return result;
        }

        String url = wxApiBaseUrl + "/cgi-bin/message/subscribe/send?access_token=" + token;

        Map<String, Object> body = new HashMap<>();
        body.put("touser", openId);
        body.put("template_id", templateId);
        if (page != null && !page.isBlank()) {
            body.put("page", page);
        }
        body.put("data", data == null ? new HashMap<>() : data);
        // 小程序版本状态：体验版测试设为 trial，正式发布改为 formal
        body.put("miniprogram_state", miniprogramState);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, body, String.class);
            String respBody = response.getBody();
            log.info("微信订阅消息发送结果: openId={}, response={}", openId, respBody);

            JSONObject json = JSON.parseObject(respBody == null ? "{}" : respBody);
            int errcode = json.getIntValue("errcode");
            String errmsg = json.getString("errmsg");

            boolean success = errcode == 0;
            result.put("success", success);
            result.put("errCode", errcode);
            result.put("errMsg", errmsg);

            // 诊断用的 raw 发送不写发送记录，避免污染一次性订阅额度
            if (!skipAuthCheck) {
                updateSendRecord(openId, templateId, success, errmsg);
            }

            // access_token 过期时清空缓存，下次重试
            if (errcode == 40001 || errcode == 42001) {
                clearAccessToken();
            }
        } catch (RestClientException e) {
            log.error("调用微信订阅消息接口异常: {}", e.getMessage());
            result.put("errMsg", "调用微信接口异常: " + e.getMessage());
            if (!skipAuthCheck) {
                updateSendRecord(openId, templateId, false, e.getMessage());
            }
        }
        return result;
    }

    @Override
    public Map<String, Object> diagnose() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> authList = new ArrayList<>();
        String testOpenId = null;
        try {
            List<WxSubscribeRecord> records = wxSubscribeRecordMapper.selectRecent();
            int count = records == null ? 0 : records.size();
            out.put("authRecordCount", count);
            if (records != null) {
                for (WxSubscribeRecord r : records) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("openId", mask(r.getOpenId()));
                    m.put("templateId", maskTemplate(r.getTemplateId()));
                    m.put("authType", r.getAuthType());
                    m.put("remainCount", r.getRemainCount());
                    m.put("sendStatus", r.getSendStatus());
                    m.put("failReason", r.getFailReason());
                    m.put("authTime", r.getAuthTime());
                    authList.add(m);
                    if (testOpenId == null && r.getOpenId() != null && isRealOpenId(r.getOpenId())) {
                        testOpenId = r.getOpenId();
                    }
                }
            }
        } catch (Exception e) {
            out.put("authQueryError", e.getMessage());
        }
        out.put("authRecords", authList);

        if (testOpenId == null) {
            out.put("testSend", Map.of("skipped", true,
                    "reason", "wx_subscribe_record 表中没有真实 openId 的授权记录——前端授权上报可能未写入，导致推送在 hasAvailableAuth 处被拦截"));
        } else {
            // 实测 health-abnormal 模板（健康预警用的模板）
            Map<String, Map<String, String>> healthData = new HashMap<>();
            putDataItem(healthData, "number1", "180");
            putDataItem(healthData, "number2", "110");
            putDataItem(healthData, "number3", "90");
            putDataItem(healthData, "number9", "6.1");
            putDataItem(healthData, "number13", "37.0");
            Map<String, Object> healthRaw = sendSubscribeMessageRaw(testOpenId, defaultHealthAbnormalTemplateId,
                    "pages/health/health", healthData);
            healthRaw.put("testTemplate", "health-abnormal");
            healthRaw.put("targetOpenId", mask(testOpenId));
            out.put("testSendHealthAbnormal", healthRaw);

            // 实测 fraud-risk 模板（反诈用的模板，库里已有授权记录）
            Map<String, Map<String, String>> fraudData = new HashMap<>();
            putDataItem(fraudData, "thing6", "血压异常预警测试");
            putDataItem(fraudData, "thing12", "中风险");
            putDataItem(fraudData, "thing13", "请勿轻信陌生来电");
            Map<String, Object> fraudRaw = sendSubscribeMessageRaw(testOpenId, defaultFraudRiskTemplateId,
                    "pages/fraud/fraud", fraudData);
            fraudRaw.put("testTemplate", "fraud-risk");
            fraudRaw.put("targetOpenId", mask(testOpenId));
            out.put("testSendFraudRisk", fraudRaw);
        }
        return out;
    }

    private String mask(String s) {
        if (s == null || s.length() <= 8) return s;
        return s.substring(0, 4) + "****" + s.substring(s.length() - 4);
    }

    private boolean isRealOpenId(String s) {
        return s != null && s.startsWith("o") && s.length() >= 20 && !s.toLowerCase().contains("demo");
    }

    private String maskTemplate(String t) {
        if (t == null) return null;
        return t.length() > 6 ? t.substring(0, 4) + "..." + t.substring(t.length() - 3) : t;
    }

    @Override
    public Map<String, Object> code2Session(String code) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        if (code == null || code.isBlank()) {
            result.put("errMsg", "code 不能为空");
            return result;
        }
        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
            result.put("errMsg", "未配置微信小程序 appId/appSecret，无法换取 openId");
            return result;
        }
        String url = wxApiBaseUrl + "/sns/jscode2session?appid=" + appId
                + "&secret=" + appSecret + "&js_code=" + code + "&grant_type=authorization_code";
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            JSONObject json = JSON.parseObject(response.getBody() == null ? "{}" : response.getBody());
            int errcode = json.getIntValue("errcode");
            if (errcode == 0) {
                result.put("success", true);
                result.put("openId", json.getString("openid"));
                result.put("sessionKey", json.getString("session_key"));
            } else {
                result.put("errMsg", json.getString("errmsg"));
            }
        } catch (Exception e) {
            log.error("调用微信 code2Session 异常: {}", e.getMessage());
            result.put("errMsg", "调用微信接口异常: " + e.getMessage());
        }
        return result;
    }

    @Override
    public Map<String, Object> sendFraudRisk(String openId, String content,
                                             String riskLevel, String advice) {
        String templateId = defaultFraudRiskTemplateId;
        Map<String, Object> result = new HashMap<>();
        if (templateId == null || templateId.isBlank()) {
            result.put("success", false);
            result.put("errMsg", "未配置反诈风险检测订阅消息模板 ID");
            return result;
        }

        String page = "pages/fraud/fraud";
        Map<String, Map<String, String>> data = new HashMap<>();
        putDataItem(data, "thing6", content);
        putDataItem(data, "thing12", riskLevel);
        putDataItem(data, "thing13", advice);

        return sendSubscribeMessage(openId, templateId, page, data);
    }

    @Override
    public Map<String, Object> sendHealthAbnormal(String openId,
                                                  String systolicPressure,
                                                  String diastolicPressure,
                                                  String heartRate,
                                                  String bloodSugar,
                                                  String temperature) {
        String templateId = defaultHealthAbnormalTemplateId;
        Map<String, Object> result = new HashMap<>();
        if (templateId == null || templateId.isBlank()) {
            result.put("success", false);
            result.put("errMsg", "未配置健康异常通知订阅消息模板 ID");
            return result;
        }

        String page = "pages/health/health";
        Map<String, Map<String, String>> data = new HashMap<>();
        putDataItem(data, "number1", systolicPressure);
        putDataItem(data, "number2", diastolicPressure);
        putDataItem(data, "number3", heartRate);
        putDataItem(data, "number9", bloodSugar);
        putDataItem(data, "number13", temperature);

        return sendSubscribeMessage(openId, templateId, page, data);
    }

    private void putDataItem(Map<String, Map<String, String>> data, String key, String value) {
        Map<String, String> item = new HashMap<>();
        // 微信要求单个字段长度不超过 20 个字符（thing 类型）
        String v = value == null ? "" : value;
        if (v.length() > 20) {
            v = v.substring(0, 19) + "…";
        }
        item.put("value", v);
        data.put(key, item);
    }

    @Override
    public boolean hasAvailableAuth(String openId, String templateId) {
        if (openId == null || openId.isBlank() || templateId == null || templateId.isBlank()) {
            return false;
        }
        List<WxSubscribeRecord> list = wxSubscribeRecordMapper.selectAvailable(openId, templateId);
        return list != null && !list.isEmpty();
    }

    private void updateSendRecord(String openId, String templateId, boolean success, String reason) {
        try {
            List<WxSubscribeRecord> list = wxSubscribeRecordMapper.selectAvailable(openId, templateId);
            if (list == null || list.isEmpty()) return;
            WxSubscribeRecord record = list.get(0);
            record.setSendTime(LocalDateTime.now());
            record.setSendStatus(success ? "success" : "fail");
            record.setFailReason(success ? null : reason);
            if ("once".equals(record.getAuthType()) && record.getRemainCount() != null && record.getRemainCount() > 0) {
                record.setRemainCount(record.getRemainCount() - 1);
            }
            wxSubscribeRecordMapper.updateById(record);
        } catch (Exception e) {
            log.warn("更新订阅消息发送记录失败: {}", e.getMessage());
        }
    }

    private String getAccessToken() {
        long now = System.currentTimeMillis();
        if (accessToken != null && now < accessTokenExpireAt - 60_000) {
            return accessToken;
        }
        synchronized (tokenLock) {
            if (accessToken != null && now < accessTokenExpireAt - 60_000) {
                return accessToken;
            }
            if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
                log.warn("未配置微信小程序 appId/appSecret，无法获取 access_token");
                return null;
            }
            String url = String.format(
                    wxApiBaseUrl + "/cgi-bin/token?grant_type=client_credential&appid=%s&secret=%s",
                    appId, appSecret);
            try {
                ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
                JSONObject json = JSON.parseObject(response.getBody() == null ? "{}" : response.getBody());
                String token = json.getString("access_token");
                Integer expiresIn = json.getInteger("expires_in");
                if (token != null && !token.isBlank()) {
                    accessToken = token;
                    accessTokenExpireAt = now + (expiresIn != null ? expiresIn : 7200) * 1000L;
                    log.info("微信 access_token 获取成功，有效期 {} 秒", expiresIn);
                    return accessToken;
                } else {
                    log.warn("获取微信 access_token 失败: {}", response.getBody());
                    return null;
                }
            } catch (Exception e) {
                log.error("获取微信 access_token 异常: {}", e.getMessage());
                return null;
            }
        }
    }

    private void clearAccessToken() {
        synchronized (tokenLock) {
            accessToken = null;
            accessTokenExpireAt = 0;
        }
    }
}

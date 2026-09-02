package com.elderly.service;

import com.elderly.entity.WxSubscribeRecord;

import java.util.Map;

/**
 * 微信小程序订阅消息服务。
 */
public interface WxSubscribeService {

    /**
     * 记录用户授权（前端调用 wx.requestSubscribeMessage 成功后上报）。
     */
    WxSubscribeRecord recordAuth(String openId, Long bindId, String templateId, String authType);

    /**
     * 向指定 openId 发送一条订阅消息。
     *
     * @param openId     接收者 openId
     * @param templateId 模板 ID（为空则使用默认配置）
     * @param page       点击消息后进入的小程序页面路径
     * @param data       模板消息数据（key-value，value 需为 { "value": "..." } 形式）
     * @return 发送结果：{ success: boolean, errCode: int, errMsg: string }
     */
    Map<String, Object> sendSubscribeMessage(String openId, String templateId, String page,
                                             Map<String, Map<String, String>> data);

    /**
     * 用 wx.login 拿到的 code 换取用户 openId（微信 code2Session）。
     *
     * @param code 前端 uni.login 返回的临时登录凭证 code
     * @return { success: boolean, openId: string, sessionKey: string, errMsg: string }
     */
    Map<String, Object> code2Session(String code);

    /**
     * 发送反诈风险检测通知（使用健康监测提醒模板）。
     *
     * @param openId    接收者 openId
     * @param content   监测内容 / 触发话术
     * @param riskLevel 风险等级（如 高 / 中 / 低）
     * @param advice    防护建议
     * @return 发送结果
     */
    Map<String, Object> sendFraudRisk(String openId, String content,
                                      String riskLevel, String advice);

    /**
     * 发送健康异常通知（使用健康检测通知模板）。
     *
     * @param openId         接收者 openId
     * @param systolicPressure 血压高压
     * @param diastolicPressure 血压低压
     * @param heartRate      心率
     * @param bloodSugar     血糖
     * @param temperature    体温
     * @return 发送结果
     */
    Map<String, Object> sendHealthAbnormal(String openId,
                                           String systolicPressure,
                                           String diastolicPressure,
                                           String heartRate,
                                           String bloodSugar,
                                           String temperature);

    /**
     * 查询某个 openId 是否已授权某个模板。
     */
    boolean hasAvailableAuth(String openId, String templateId);
}

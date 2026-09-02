package com.elderly.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 微信小程序订阅消息授权与发送记录。
 *
 * 说明：
 * - auth_type = 'once'：一次性订阅，用户每次授权可发 1 条；
 * - auth_type = 'permanent'：长期订阅（需申请长期订阅模板）。
 */
@Data
public class WxSubscribeRecord {

    private Long id;

    /** 接收者 openId */
    private String openId;

    /** 关联 family_bind.id */
    private Long bindId;

    /** 模板 ID */
    private String templateId;

    /** 授权类型：once / permanent */
    private String authType;

    /** 剩余可发送次数（一次性订阅用） */
    private Integer remainCount;

    /** 最近授权时间 */
    private LocalDateTime authTime;

    /** 最近发送时间 */
    private LocalDateTime sendTime;

    /** 发送状态：success / fail */
    private String sendStatus;

    /** 发送失败原因 */
    private String failReason;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}

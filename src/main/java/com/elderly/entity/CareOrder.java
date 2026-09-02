package com.elderly.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 护工服务工单表 —— AI根据老人需求自动生成
 */
@Data
public class CareOrder {

    private Long id;

    /** 关联老人用户ID */
    private Long userId;

    /** 老人姓名 */
    private String elderlyName;

    /** 老人地址 */
    private String address;

    /** 老人电话 */
    private String phone;

    /** 工单类型：health(健康服务) / daily(日常照料) / emergency(紧急求助) */
    private String orderType;

    /** 老人原始需求描述 */
    private String demand;

    /** AI生成的工单内容 */
    private String orderContent;

    /** 是否需要携带医疗设备：0-否 1-是 */
    private Integer needMedicalDevice;

    /** 工单状态：pending(待处理) / assigned(已派单) / done(已完成) / cancelled(已取消) */
    private String status;

    /** 处理人（护工）姓名 */
    private String handlerName;

    /** 指派护工ID（加权调度结果） */
    private Long caregiverId;

    /** 派单时间 */
    private LocalDateTime assignedTime;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 完成时间 */
    private LocalDateTime finishTime;

    /** 非数据库字段：老人健康摘要（工单生成Prompt入参，由Agent5传入） */
    private transient String healthSummary;
}

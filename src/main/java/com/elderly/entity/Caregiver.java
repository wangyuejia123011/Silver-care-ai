package com.elderly.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 护工信息表 —— Agent5 加权调度的数据基础
 */
@Data
public class Caregiver {

    private Long id;

    /** 护工姓名 */
    private String name;

    /** 手机号 */
    private String phone;

    /** 性别 */
    private String gender;

    /** 年龄 */
    private Integer age;

    /** 技能标签JSON字符串，如 ["助浴","康复","陪诊"] */
    private String skills;

    /** 负责区域（如：东区/西区/3号楼） */
    private String area;

    /** 当前接单数（负载均衡依据） */
    private Integer currentOrderCount;

    /** 累计接单数（日报排行依据） */
    private Integer totalOrderCount;

    /** 状态：on-在岗 off-休息 */
    private String status;

    /** 能否携带医疗设备上门：0-否 1-是（健康类工单加权匹配依据） */
    private Integer canCarryDevice;

    /** 头像图片地址 */
    private String avatar;

    /** 个人简介/详细介绍 */
    private String bio;

    /** 从业年限 */
    private Integer experienceYears;

    /** 服务评分 */
    private java.math.BigDecimal rating;

    /** 评价人数 */
    private Integer ratingCount;

    /** 可服务时间，如「周一至周日 8:00-18:00」 */
    private String serviceTime;

    /** 创建时间 */
    private LocalDateTime createTime;
}

package com.elderly.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 老人用户信息表
 */
@Data
public class ElderlyUser {

    private Long id;

    /** 微信openid */
    private String openId;

    /** 老人姓名 */
    private String name;

    /** 手机号 */
    private String phone;

    /** 性别：男/女 */
    private String gender;

    /** 年龄 */
    private Integer age;

    /** 居住地址 */
    private String address;

    /** 紧急联系人姓名 */
    private String emergencyContact;

    /** 紧急联系人电话 */
    private String emergencyPhone;

    /** 头像URL */
    private String avatar;

    /** 备注（既往病史等） */
    private String remark;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}

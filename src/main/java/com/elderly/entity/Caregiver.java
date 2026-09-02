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

    /** 创建时间 */
    private LocalDateTime createTime;
}

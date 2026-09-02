package com.elderly.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 老人与家属/护工绑定关系
 */
@Data
public class FamilyBind {

    private Long id;
    private Long elderlyUserId;
    /** family / caregiver */
    private String role;
    private String name;
    private String phone;
    private String openId;
    private Long caregiverId;
    private Integer notifyEnabled;
    private LocalDateTime createTime;

    /** 查询时附带老人姓名（非表字段） */
    private String elderlyName;
    private String elderlyAddress;
}

package com.elderly.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用户登录请求
 */
@Data
public class LoginRequest {
    /** 微信openid */
    @NotBlank(message = "openId不能为空")
    @Size(max = 64, message = "openId长度不能超过64")
    private String openId;

    /** 手机号（可选） */
    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;
}

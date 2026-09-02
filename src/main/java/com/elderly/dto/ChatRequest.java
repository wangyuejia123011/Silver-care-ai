package com.elderly.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 文字对话请求
 */
@Data
public class ChatRequest {
    /** 用户ID */
    @NotNull(message = "用户ID不能为空")
    private Long userId;

    /** 消息内容 */
    @NotBlank(message = "消息内容不能为空")
    @Size(max = 2000, message = "消息内容不能超过2000字")
    private String message;
}

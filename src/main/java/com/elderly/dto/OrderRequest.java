package com.elderly.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 工单创建请求
 */
@Data
public class OrderRequest {
    @NotNull(message = "用户ID不能为空")
    private Long userId;

    @Size(max = 50, message = "老人姓名不能超过50字")
    private String elderlyName;

    @Size(max = 200, message = "地址不能超过200字")
    private String address;

    @Size(max = 20, message = "电话不能超过20字")
    private String phone;

    @Size(max = 20, message = "工单类型不能超过20字")
    private String orderType;

    @NotBlank(message = "需求描述不能为空")
    @Size(max = 500, message = "需求描述不能超过500字")
    private String demand;
}

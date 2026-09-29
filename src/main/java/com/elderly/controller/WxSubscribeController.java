package com.elderly.controller;

import com.elderly.common.R;
import com.elderly.service.WxSubscribeService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 微信小程序订阅消息接口。
 */
@RestController
@RequestMapping("/api/wx-subscribe")
public class WxSubscribeController {

    @Resource
    private WxSubscribeService wxSubscribeService;

    /**
     * 记录用户授权（前端调用 wx.requestSubscribeMessage 成功后上报）。
     */
    @PostMapping("/auth")
    public R<Void> recordAuth(@RequestBody Map<String, String> body) {
        String openId = body.get("openId");
        String templateId = body.get("templateId");
        String authType = body.get("authType");
        String bindIdStr = body.get("bindId");
        Long bindId = null;
        if (bindIdStr != null && !bindIdStr.isBlank()) {
            try {
                bindId = Long.parseLong(bindIdStr);
            } catch (NumberFormatException ignored) {
            }
        }
        wxSubscribeService.recordAuth(openId, bindId, templateId, authType);
        return R.success("授权记录已保存");
    }

    /**
     * 查询是否已授权。
     */
    @GetMapping("/check")
    public R<Map<String, Boolean>> check(@RequestParam String openId,
                                         @RequestParam(required = false) String templateId) {
        boolean ok = wxSubscribeService.hasAvailableAuth(openId, templateId);
        return R.success(Map.of("authorized", ok));
    }

    /**
     * 诊断推送链路：返回授权记录列表 + 对首个真实 openId 的实测发送结果（含微信原始 errcode/errmsg）。
     */
    @GetMapping("/diagnose")
    public R<Map<String, Object>> diagnose() {
        return R.success(wxSubscribeService.diagnose());
    }
}

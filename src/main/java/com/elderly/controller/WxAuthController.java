package com.elderly.controller;

import com.elderly.common.R;
import com.elderly.service.WxSubscribeService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 微信小程序鉴权相关接口（code2Session 等）。
 */
@RestController
@RequestMapping("/api/wx")
public class WxAuthController {

    @Resource
    private WxSubscribeService wxSubscribeService;

    /**
     * 用 wx.login 返回的 code 换取用户 openId。
     * 请求体：{ "code": "前端 uni.login 得到的临时登录凭证" }
     * 返回：R.data = { success, openId, sessionKey }
     */
    @PostMapping("/code2session")
    public R<Map<String, Object>> code2Session(@RequestBody(required = false) Map<String, String> body) {
        String code = body == null ? null : body.get("code");
        Map<String, Object> result = wxSubscribeService.code2Session(code);
        if (Boolean.TRUE.equals(result.get("success"))) {
            return R.success(result);
        }
        return R.fail(400, (String) result.get("errMsg"));
    }
}

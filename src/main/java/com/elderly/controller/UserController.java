package com.elderly.controller;

import com.elderly.common.R;
import com.elderly.dto.LoginRequest;
import com.elderly.entity.ElderlyUser;
import com.elderly.service.ElderlyUserService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * 老人用户接口 —— 登录注册、信息管理
 */
@RestController
@RequestMapping("/api/user")
public class UserController {

    @Resource
    private ElderlyUserService userService;

    /**
     * 微信登录（根据openId查询或自动注册）
     */
    @PostMapping("/login")
    public R<ElderlyUser> login(@Valid @RequestBody LoginRequest req) {
        if (req.getOpenId() == null || req.getOpenId().isBlank()) {
            return R.fail(400, "openId不能为空");
        }
        ElderlyUser user = userService.loginByOpenId(req.getOpenId());
        return R.success("登录成功", user);
    }

    /**
     * 注册/完善信息
     */
    @PostMapping("/register")
    public R<ElderlyUser> register(@RequestBody ElderlyUser user) {
        ElderlyUser saved = userService.register(user);
        return R.success("注册成功", saved);
    }

    /**
     * 更新用户信息
     */
    @PutMapping("/update")
    public R<Void> update(@RequestBody ElderlyUser user) {
        userService.updateProfile(user);
        return R.success("更新成功");
    }

    /**
     * 根据ID查询用户信息
     */
    @GetMapping("/{id}")
    public R<ElderlyUser> getById(@PathVariable Long id) {
        ElderlyUser user = userService.getById(id);
        if (user == null) {
            return R.fail(404, "用户不存在");
        }
        return R.success(user);
    }

    /**
     * 查询全部用户
     */
    @GetMapping("/list")
    public R<List<ElderlyUser>> listAll() {
        return R.success(userService.listAll());
    }
}

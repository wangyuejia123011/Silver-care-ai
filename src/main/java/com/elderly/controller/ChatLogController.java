package com.elderly.controller;

import com.elderly.common.R;
import com.elderly.entity.ChatLog;
import com.elderly.service.ChatLogService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * 对话记录接口 —— 查询历史对话
 */
@RestController
@RequestMapping("/api/chatlog")
public class ChatLogController {

    @Resource
    private ChatLogService chatLogService;

    /**
     * 查询用户对话历史
     */
    @GetMapping("/list/{userId}")
    public R<List<ChatLog>> list(@PathVariable Long userId) {
        return R.success(chatLogService.listByUserId(userId));
    }

    /**
     * 查询最近N条对话
     */
    @GetMapping("/recent")
    public R<List<ChatLog>> recent(@RequestParam(defaultValue = "20") int limit) {
        return R.success(chatLogService.listRecent(limit));
    }
}

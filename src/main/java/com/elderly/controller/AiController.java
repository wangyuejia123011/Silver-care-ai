package com.elderly.controller;

import com.elderly.common.R;
import com.elderly.dto.ChatRequest;
import com.elderly.entity.ChatLog;
import com.elderly.service.ChatLogService;
import com.elderly.util.LlmUtil;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * AI对话接口 —— 文字输入，流式/同步回复
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    @Resource
    private LlmUtil llmUtil;
    @Resource
    private ChatLogService chatLogService;

    /**
     * 文字对话（同步返回完整回复）
     */
    @PostMapping("/chat")
    public R<String> chat(@RequestBody ChatRequest req) {
        if (req.getMessage() == null || req.getMessage().isBlank()) {
            return R.fail(400, "消息内容不能为空");
        }
        String reply = llmUtil.chatSync(req.getMessage());

        // 保存对话记录
        try {
            ChatLog log = new ChatLog();
            log.setUserId(req.getUserId());
            log.setChatType("chat");
            log.setUserInput(req.getMessage());
            log.setAiReply(reply);
            log.setSource("text");
            chatLogService.save(log);
        } catch (Exception ignored) {
        }

        return R.success("回复成功", reply);
    }

    /**
     * 文字对话（SSE流式返回，打字机效果）
     * 说明：MVC对Flux<String>+text/event-stream会自动包装data:前缀，无需手动拼接
     */
    @PostMapping(value = "/chat/stream", produces = "text/event-stream")
    public Flux<String> chatStream(@RequestBody ChatRequest req) {
        if (req.getMessage() == null || req.getMessage().isBlank()) {
            return Flux.just("消息内容不能为空");
        }
        return llmUtil.streamChat(req.getMessage());
    }
}

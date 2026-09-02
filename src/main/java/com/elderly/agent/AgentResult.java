package com.elderly.agent;

import com.elderly.entity.CareOrder;
import lombok.Data;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 子Agent执行结果 —— 供统一调度层组装SSE事件流。
 */
@Data
public class AgentResult {

    /** 对话类型：health/fraud/order/chat */
    private String chatType;

    /** 主回复文本流（打字机效果的正文） */
    private Flux<String> stream;

    /** 反诈风险JSON（命中时前端弹全屏红色警告） */
    private String riskJson;

    /** 自动生成的工单（高危预警/服务请求时），推送给前端和护工端 */
    private CareOrder order;

    /** 健康风险通知摘要JSON（达到通知条件时） */
    private String healthNotifyJson;

    /** 风险等级：low/medium/high */
    private String riskLevel;

    /** 情绪标签：低落/正常（情感陪伴用） */
    private String emotion;

    /**
     * 反诈 RAG 比对命中的案例片段（反诈专用）。
     * 前端可在反诈警告页用卡片列出案例摘要，帮助老人理解"为什么被拦"。
     */
    private List<String> matchedCases;

    public static AgentResult of(String chatType, Flux<String> stream) {
        AgentResult r = new AgentResult();
        r.chatType = chatType;
        r.stream = stream;
        return r;
    }
}

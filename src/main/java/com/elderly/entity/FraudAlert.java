package com.elderly.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 反诈预警记录表 —— 记录每次反诈检测的结果
 */
@Data
public class FraudAlert {

    private Long id;

    /** 关联老人用户ID */
    private Long userId;

    /** 老人姓名 */
    private String elderlyName;

    /** 原始对话内容 */
    private String content;

    /** 命中的风险关键词（逗号分隔） */
    private String hitKeywords;

    /** 风险等级：low(低) / medium(中) / high(高) */
    private String riskLevel;

    /** 检测方式：keyword(关键词) / ai(大模型语义) / rag(案例库) / ai+rag */
    private String detectType;

    /** AI给出的提示信息 */
    private String aiTip;

    /** RAG 命中的相似反诈案例片段，多条用换行分隔（最多保留3条，可空） */
    private String matchedCases;

    /** 是否已处理：0-未处理 1-已处理 */
    private Integer isHandled;

    private LocalDateTime createTime;
}

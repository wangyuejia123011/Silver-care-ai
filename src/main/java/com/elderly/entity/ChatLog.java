package com.elderly.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 对话记录表 —— 记录老人与AI的每次对话
 */
@Data
public class ChatLog {

    private Long id;

    /** 关联老人用户ID */
    private Long userId;

    /** 对话类型：health(健康) / fraud(反诈) / order(服务) / chat(闲聊) */
    private String chatType;

    /** 用户输入内容 */
    private String userInput;

    /** AI回复内容 */
    private String aiReply;

    /** 情绪标签：低落/正常 */
    private String emotion;

    /** 对话来源：voice(语音) / text(文字) */
    private String source;

    /** 创建时间 */
    private LocalDateTime createTime;
}

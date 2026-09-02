package com.elderly.service;

import com.elderly.entity.ChatLog;
import java.util.List;

public interface ChatLogService {

    /** 保存对话记录 */
    void save(ChatLog log);

    /** 查询用户对话历史 */
    List<ChatLog> listByUserId(Long userId);

    /** 查询最近N条对话 */
    List<ChatLog> listRecent(int limit);
}

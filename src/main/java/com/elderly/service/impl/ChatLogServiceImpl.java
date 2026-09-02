package com.elderly.service.impl;

import com.elderly.entity.ChatLog;
import com.elderly.mapper.ChatLogMapper;
import com.elderly.service.ChatLogService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class ChatLogServiceImpl implements ChatLogService {

    @Resource
    private ChatLogMapper chatLogMapper;

    @Override
    public void save(ChatLog log) {
        chatLogMapper.insert(log);
    }

    @Override
    public List<ChatLog> listByUserId(Long userId) {
        return chatLogMapper.selectByUserId(userId);
    }

    @Override
    public List<ChatLog> listRecent(int limit) {
        return chatLogMapper.selectRecent(limit);
    }
}

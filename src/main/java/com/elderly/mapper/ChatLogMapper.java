package com.elderly.mapper;

import com.elderly.entity.ChatLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface ChatLogMapper {

    /** 新增对话记录 */
    int insert(ChatLog log);

    /** 根据用户ID查询对话历史 */
    List<ChatLog> selectByUserId(@Param("userId") Long userId);

    /** 查询最近N条对话 */
    List<ChatLog> selectRecent(@Param("limit") int limit);
}

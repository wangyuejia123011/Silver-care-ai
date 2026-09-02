package com.elderly.mapper;

import com.elderly.entity.WxSubscribeRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface WxSubscribeRecordMapper {

    int insert(WxSubscribeRecord record);

    int updateById(WxSubscribeRecord record);

    /**
     * 查询某个 openId 对某个模板可用的授权记录（长期订阅 或 一次性订阅 remain_count > 0）
     */
    List<WxSubscribeRecord> selectAvailable(@Param("openId") String openId,
                                            @Param("templateId") String templateId);

    /**
     * 查询某个 openId 的最近一次授权记录
     */
    WxSubscribeRecord selectLatestByOpenId(@Param("openId") String openId,
                                           @Param("templateId") String templateId);
}

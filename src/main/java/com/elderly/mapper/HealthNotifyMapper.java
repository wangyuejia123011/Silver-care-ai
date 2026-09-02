package com.elderly.mapper;

import com.elderly.entity.HealthNotify;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface HealthNotifyMapper {

    int insert(HealthNotify notify);

    HealthNotify selectById(@Param("id") Long id);

    int markRead(@Param("id") Long id);

    List<HealthNotify> selectByReceiverOpenId(@Param("openId") String openId);

    List<HealthNotify> selectByCaregiverId(@Param("caregiverId") Long caregiverId);

    int countUnreadByOpenId(@Param("openId") String openId);

    int countUnreadByCaregiverId(@Param("caregiverId") Long caregiverId);
}

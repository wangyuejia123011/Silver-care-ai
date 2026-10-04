package com.elderly.mapper;

import com.elderly.entity.FamilyBind;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface FamilyBindMapper {

    int insert(FamilyBind bind);

    int updateById(FamilyBind bind);

    int deleteById(@Param("id") Long id);

    FamilyBind selectById(@Param("id") Long id);

    List<FamilyBind> selectByElderlyUserId(@Param("elderlyUserId") Long elderlyUserId);

    List<FamilyBind> selectEnabledByElderlyUserId(@Param("elderlyUserId") Long elderlyUserId);

    FamilyBind selectByOpenId(@Param("openId") String openId);

    List<FamilyBind> selectByCaregiverId(@Param("caregiverId") Long caregiverId);

    /** 注销老人时清理其全部绑定关系（家属/护工） */
    int deleteByElderlyUserId(@Param("elderlyUserId") Long elderlyUserId);
}

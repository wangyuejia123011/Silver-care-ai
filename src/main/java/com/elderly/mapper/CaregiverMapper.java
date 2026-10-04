package com.elderly.mapper;

import com.elderly.entity.Caregiver;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface CaregiverMapper {

    /** 新增护工 */
    int insert(Caregiver caregiver);

    /** 根据ID查询（基础字段，线上库未升级时也能用） */
    Caregiver selectById(@Param("id") Long id);

    /** 根据ID查询完整档案（含头像/简介/评分等扩展列，线上库未升级时会抛异常，由Service降级） */
    Caregiver selectProfileById(@Param("id") Long id);

    /** 更新护工扩展资料（只更新非空的扩展字段） */
    int updateProfile(Caregiver caregiver);

    /** 查询全部在岗护工 */
    List<Caregiver> selectAll();

    /** 按技能标签模糊查询在岗护工（skills LIKE '%"康复"%'） */
    List<Caregiver> selectBySkill(@Param("skill") String skill);

    /** 当前接单数 +1，累计接单数 +1 */
    int increaseOrderCount(@Param("id") Long id);

    /** 当前接单数 -1 */
    int decreaseOrderCount(@Param("id") Long id);

    /** 查询累计接单最多的护工（日报排行） */
    List<Caregiver> selectTopByTotal(@Param("limit") int limit);
}

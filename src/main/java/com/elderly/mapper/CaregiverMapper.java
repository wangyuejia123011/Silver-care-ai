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

    /**
     * 只更新评分与评价人数（工单评价后回写护工档案）。
     * 字段为 null 时不参与更新。
     */
    int updateRating(Caregiver caregiver);

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

    /**
     * 注销护工：物理删除该护工档案。
     * 不做软删除，因为注销后不应再出现在调度候选池（selectAll 只查 status='on'）。
     * 名下工单的 caregiver_id 是普通索引列、无外键约束，不会阻塞删除。
     */
    int deleteById(@Param("id") Long id);
}

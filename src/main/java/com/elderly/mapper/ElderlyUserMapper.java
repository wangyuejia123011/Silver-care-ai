package com.elderly.mapper;

import com.elderly.entity.ElderlyUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface ElderlyUserMapper {

    /** 根据ID查询 */
    ElderlyUser selectById(@Param("id") Long id);

    /** 根据openId查询（微信登录用） */
    ElderlyUser selectByOpenId(@Param("openId") String openId);

    /** 新增用户 */
    int insert(ElderlyUser user);

    /** 更新用户信息 */
    int update(ElderlyUser user);

    /** 查询全部用户 */
    List<ElderlyUser> selectAll();

    /**
     * 注销老人账号（物理删除档案）。
     * 关联数据（工单、绑定关系、健康记录）由 Service 层先清，避免残留孤儿数据。
     */
    int deleteById(@Param("id") Long id);
}

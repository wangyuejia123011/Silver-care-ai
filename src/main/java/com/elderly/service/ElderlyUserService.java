package com.elderly.service;

import com.elderly.entity.ElderlyUser;
import java.util.List;

public interface ElderlyUserService {

    /** 微信登录（根据openId查询或自动注册） */
    ElderlyUser loginByOpenId(String openId);

    /** 注册/完善信息 */
    ElderlyUser register(ElderlyUser user);

    /** 更新用户信息 */
    boolean updateProfile(ElderlyUser user);

    /** 根据ID查询 */
    ElderlyUser getById(Long id);

    /** 查询全部用户 */
    List<ElderlyUser> listAll();

    /**
     * 注销老人账号：先清关联数据（工单、绑定关系），再删档案。
     * 返回 null 表示成功；返回中文错误文案表示失败（用户不存在）。
     */
    String deleteUser(Long id);
}

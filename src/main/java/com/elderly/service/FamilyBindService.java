package com.elderly.service;

import com.elderly.entity.FamilyBind;

import java.util.List;

/**
 * 家属/护工绑定关系服务
 *
 * 从 FamilyNotifyController 中下沉 Mapper 调用，统一分层架构。
 */
public interface FamilyBindService {

    /** 查询老人的所有绑定关系 */
    List<FamilyBind> listByElderlyUserId(Long elderlyUserId);

    /** 查询老人的启用通知的绑定关系（notifyEnabled=1） */
    List<FamilyBind> listEnabledByElderlyUserId(Long elderlyUserId);

    /** 按 openId 查询绑定关系（家属端登录） */
    FamilyBind getByOpenId(String openId);

    /** 新增绑定 */
    FamilyBind addBind(FamilyBind bind);

    /** 更新绑定（更新 openId / 通知开关等） */
    FamilyBind updateBind(FamilyBind bind);

    /** 解除绑定 */
    boolean deleteBind(Long id);

    /** 根据ID查询 */
    FamilyBind getById(Long id);
}

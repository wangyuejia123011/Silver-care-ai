package com.elderly.service.impl;

import com.elderly.entity.ElderlyUser;
import com.elderly.mapper.CareOrderMapper;
import com.elderly.mapper.ElderlyUserMapper;
import com.elderly.mapper.FamilyBindMapper;
import com.elderly.mapper.HealthRecordMapper;
import com.elderly.service.ElderlyUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import jakarta.annotation.Resource;
import java.util.List;

@Service
public class ElderlyUserServiceImpl implements ElderlyUserService {

    private static final Logger log = LoggerFactory.getLogger(ElderlyUserServiceImpl.class);

    @Resource
    private ElderlyUserMapper userMapper;
    @Resource
    private FamilyBindMapper familyBindMapper;
    @Resource
    private CareOrderMapper careOrderMapper;
    @Resource
    private HealthRecordMapper healthRecordMapper;

    @Override
    public ElderlyUser loginByOpenId(String openId) {
        ElderlyUser user = userMapper.selectByOpenId(openId);
        if (user == null) {
            // 首次登录自动创建空档案
            user = new ElderlyUser();
            user.setOpenId(openId);
            userMapper.insert(user);
            user = userMapper.selectByOpenId(openId);
        }
        return user;
    }

    @Override
    public ElderlyUser register(ElderlyUser user) {
        if (user.getId() != null) {
            userMapper.update(user);
        } else if (user.getOpenId() != null) {
            ElderlyUser existing = userMapper.selectByOpenId(user.getOpenId());
            if (existing != null) {
                user.setId(existing.getId());
                userMapper.update(user);
            } else {
                userMapper.insert(user);
            }
        } else {
            userMapper.insert(user);
        }
        return user;
    }

    @Override
    public boolean updateProfile(ElderlyUser user) {
        if (user.getId() == null) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        return userMapper.update(user) > 0;
    }

    @Override
    public ElderlyUser getById(Long id) {
        return userMapper.selectById(id);
    }

    @Override
    public List<ElderlyUser> listAll() {
        return userMapper.selectAll();
    }

    /**
     * 注销老人账号。顺序很重要：先清子表再删主表，避免残留孤儿数据。
     *   1) 家属/护工绑定关系 family_bind.elderly_user_id
     *   2) 该老人的全部工单 care_order.user_id
     *   3) 老人档案 elderly_user
     * 健康记录表按 user_id 关联，一并清理。
     * 任一步失败都记日志但不中断，尽量把能清的都清掉。
     */
    @Override
    public String deleteUser(Long id) {
        if (id == null) {
            return "用户ID不能为空";
        }
        if (userMapper.selectById(id) == null) {
            return "账号不存在或已注销";
        }

        try {
            familyBindMapper.deleteByElderlyUserId(id);
        } catch (Exception e) {
            log.warn("清理老人[{}]绑定关系失败: {}", id, e.getMessage());
        }
        try {
            careOrderMapper.deleteByUserId(id);
        } catch (Exception e) {
            log.warn("清理老人[{}]工单失败: {}", id, e.getMessage());
        }
        try {
            healthRecordMapper.deleteByUserId(id);
        } catch (Exception e) {
            log.warn("清理老人[{}]健康记录失败: {}", id, e.getMessage());
        }

        int rows = userMapper.deleteById(id);
        if (rows == 0) {
            return "注销失败，账号可能已被删除";
        }
        log.info("老人[{}]账号已注销，关联的绑定/工单/健康记录已清理", id);
        return null;
    }
}

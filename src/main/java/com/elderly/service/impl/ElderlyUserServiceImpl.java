package com.elderly.service.impl;

import com.elderly.entity.ElderlyUser;
import com.elderly.mapper.ElderlyUserMapper;
import com.elderly.service.ElderlyUserService;
import org.springframework.stereotype.Service;
import jakarta.annotation.Resource;
import java.util.List;

@Service
public class ElderlyUserServiceImpl implements ElderlyUserService {

    @Resource
    private ElderlyUserMapper userMapper;

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
}

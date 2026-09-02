package com.elderly.service.impl;

import com.elderly.entity.FamilyBind;
import com.elderly.mapper.FamilyBindMapper;
import com.elderly.service.FamilyBindService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class FamilyBindServiceImpl implements FamilyBindService {

    private static final Logger log = LoggerFactory.getLogger(FamilyBindServiceImpl.class);

    @Resource
    private FamilyBindMapper familyBindMapper;

    @Override
    public List<FamilyBind> listByElderlyUserId(Long elderlyUserId) {
        return familyBindMapper.selectByElderlyUserId(elderlyUserId);
    }

    @Override
    public List<FamilyBind> listEnabledByElderlyUserId(Long elderlyUserId) {
        return familyBindMapper.selectEnabledByElderlyUserId(elderlyUserId);
    }

    @Override
    public FamilyBind getByOpenId(String openId) {
        return familyBindMapper.selectByOpenId(openId);
    }

    @Override
    public FamilyBind addBind(FamilyBind bind) {
        if (bind.getElderlyUserId() == null || bind.getRole() == null || bind.getName() == null) {
            throw new IllegalArgumentException("老人ID、角色和姓名不能为空");
        }
        if (!"family".equals(bind.getRole()) && !"caregiver".equals(bind.getRole())) {
            throw new IllegalArgumentException("角色只能是 family 或 caregiver");
        }
        if (bind.getNotifyEnabled() == null) {
            bind.setNotifyEnabled(1);
        }
        familyBindMapper.insert(bind);
        log.info("新增绑定关系: elderlyUserId={}, role={}, name={}",
                bind.getElderlyUserId(), bind.getRole(), bind.getName());
        return bind;
    }

    @Override
    public FamilyBind updateBind(FamilyBind bind) {
        if (bind == null || bind.getId() == null) {
            throw new IllegalArgumentException("绑定ID不能为空");
        }
        if (bind.getOpenId() != null && bind.getOpenId().isBlank()) {
            bind.setOpenId(null);
        }
        int rows = familyBindMapper.updateById(bind);
        if (rows <= 0) {
            throw new IllegalArgumentException("未找到该绑定记录");
        }
        log.info("更新绑定关系: id={}, openId={}", bind.getId(), bind.getOpenId());
        return familyBindMapper.selectById(bind.getId());
    }

    @Override
    public boolean deleteBind(Long id) {
        int rows = familyBindMapper.deleteById(id);
        if (rows > 0) {
            log.info("解除绑定关系: id={}", id);
        }
        return rows > 0;
    }

    @Override
    public FamilyBind getById(Long id) {
        return familyBindMapper.selectById(id);
    }
}

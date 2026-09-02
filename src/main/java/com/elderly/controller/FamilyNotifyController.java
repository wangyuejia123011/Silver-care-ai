package com.elderly.controller;

import com.elderly.common.R;
import com.elderly.entity.FamilyBind;
import com.elderly.entity.HealthNotify;
import com.elderly.service.FamilyBindService;
import com.elderly.service.HealthNotifyService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 家属/护工绑定与健康通知
 *
 * 优化：Controller 不再直接调用 Mapper，统一通过 Service 层操作，
 * 业务逻辑（参数校验、默认值设置）下沉到 Service 实现类。
 */
@RestController
@RequestMapping("/api")
public class FamilyNotifyController {

    @Resource
    private FamilyBindService familyBindService;
    @Resource
    private HealthNotifyService healthNotifyService;

    @GetMapping("/bind/list/{elderlyUserId}")
    public R<List<FamilyBind>> listBind(@PathVariable Long elderlyUserId) {
        return R.success(familyBindService.listByElderlyUserId(elderlyUserId));
    }

    @PostMapping("/bind")
    public R<FamilyBind> addBind(@RequestBody FamilyBind bind) {
        try {
            FamilyBind saved = familyBindService.addBind(bind);
            return R.success("绑定成功", saved);
        } catch (IllegalArgumentException e) {
            return R.fail(400, e.getMessage());
        }
    }

    @DeleteMapping("/bind/{id}")
    public R<Void> deleteBind(@PathVariable Long id) {
        familyBindService.deleteBind(id);
        return R.success("已解除绑定");
    }

    @PutMapping("/bind/{id}")
    public R<FamilyBind> updateBind(@PathVariable Long id, @RequestBody FamilyBind bind) {
        bind.setId(id);
        try {
            FamilyBind updated = familyBindService.updateBind(bind);
            return R.success("更新成功", updated);
        } catch (IllegalArgumentException e) {
            return R.fail(400, e.getMessage());
        }
    }

    /** 家属端登录：按 openId 查绑定关系 */
    @GetMapping("/bind/by-openid/{openId}")
    public R<FamilyBind> byOpenId(@PathVariable String openId) {
        FamilyBind bind = familyBindService.getByOpenId(openId);
        if (bind == null) {
            return R.fail(404, "未找到家属绑定");
        }
        return R.success(bind);
    }

    @GetMapping("/notify/inbox")
    public R<List<HealthNotify>> inbox(@RequestParam(required = false) String openId,
                                       @RequestParam(required = false) Long caregiverId) {
        if (openId != null && !openId.isBlank()) {
            return R.success(healthNotifyService.inboxByOpenId(openId));
        }
        if (caregiverId != null) {
            return R.success(healthNotifyService.inboxByCaregiverId(caregiverId));
        }
        return R.fail(400, "请传入 openId 或 caregiverId");
    }

    @GetMapping("/notify/{id}")
    public R<HealthNotify> notifyDetail(@PathVariable Long id) {
        HealthNotify notify = healthNotifyService.getById(id);
        if (notify == null) {
            return R.fail(404, "通知不存在");
        }
        return R.success(notify);
    }

    @PutMapping("/notify/read/{id}")
    public R<Void> markRead(@PathVariable Long id) {
        healthNotifyService.markRead(id);
        return R.success("已读");
    }

    @GetMapping("/notify/unread")
    public R<Map<String, Integer>> unread(@RequestParam(required = false) String openId,
                                          @RequestParam(required = false) Long caregiverId) {
        Map<String, Integer> data = new HashMap<>();
        int count = 0;
        if (openId != null && !openId.isBlank()) {
            count = healthNotifyService.countUnreadByOpenId(openId);
        } else if (caregiverId != null) {
            count = healthNotifyService.countUnreadByCaregiverId(caregiverId);
        }
        data.put("count", count);
        return R.success(data);
    }
}

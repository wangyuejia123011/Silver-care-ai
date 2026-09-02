package com.elderly.controller;

import com.elderly.common.R;
import com.elderly.entity.CareOrder;
import com.elderly.entity.Caregiver;
import com.elderly.service.CareOrderService;
import com.elderly.service.CaregiverService;
import jakarta.annotation.Resource;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 护工端接口 —— 护工小程序使用：查看工单、接单、完成
 */
@RestController
@RequestMapping("/api/caregiver")
public class CaregiverController {

    @Resource
    private CaregiverService caregiverService;
    @Resource
    private CareOrderService careOrderService;

    /** 在岗护工列表（含技能/负载，管理端展示调度结果用） */
    @GetMapping("/list")
    public R<List<Caregiver>> list() {
        return R.success(caregiverService.listAll());
    }

    /** 护工详情 */
    @GetMapping("/{id}")
    public R<Caregiver> getById(@PathVariable Long id) {
        Caregiver caregiver = caregiverService.getById(id);
        if (caregiver == null) {
            return R.fail(404, "护工不存在");
        }
        return R.success(caregiver);
    }

    /** 新增护工（管理端 / 护工端自主注册） */
    @PostMapping("/add")
    public R<Caregiver> add(@RequestBody Caregiver caregiver) {
        if (caregiver == null) {
            return R.fail(400, "注册信息不能为空");
        }
        if (!StringUtils.hasText(caregiver.getName())) {
            return R.fail(400, "请输入护工姓名");
        }
        if (!StringUtils.hasText(caregiver.getPhone())) {
            return R.fail(400, "请输入手机号");
        }
        if (!StringUtils.hasText(caregiver.getArea())) {
            return R.fail(400, "请选择负责区域");
        }
        return R.success("注册成功", caregiverService.addCaregiver(caregiver));
    }

    /** 我的工单（护工端首页） */
    @GetMapping("/orders/{caregiverId}")
    public R<List<CareOrder>> myOrders(@PathVariable Long caregiverId) {
        return R.success(careOrderService.listByCaregiverId(caregiverId));
    }

    /** 护工今日工作台：今日工单数、待完成数、累计接单 */
    @GetMapping("/dashboard/{caregiverId}")
    public R<Map<String, Object>> dashboard(@PathVariable Long caregiverId) {
        Caregiver caregiver = caregiverService.getById(caregiverId);
        if (caregiver == null) {
            return R.fail(404, "护工不存在");
        }
        List<CareOrder> orders = careOrderService.listByCaregiverId(caregiverId);
        long pending = orders.stream().filter(o -> "assigned".equals(o.getStatus())).count();
        Map<String, Object> data = new HashMap<>();
        data.put("caregiver", caregiver);
        data.put("todayOrders", orders.size());
        data.put("pendingOrders", pending);
        data.put("totalOrders", caregiver.getTotalOrderCount());
        return R.success(data);
    }
}

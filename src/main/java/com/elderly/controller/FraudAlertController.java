package com.elderly.controller;

import com.elderly.common.R;
import com.elderly.entity.FraudAlert;
import com.elderly.service.FraudAlertService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import java.util.Collections;
import java.util.List;

/**
 * 反诈预警接口 —— 查询反诈记录
 */
@Slf4j
@RestController
@RequestMapping("/api/fraud")
public class FraudAlertController {

    @Resource
    private FraudAlertService fraudAlertService;

    /**
     * 查询用户反诈记录
     */
    @GetMapping("/list/{userId}")
    public R<List<FraudAlert>> list(@PathVariable Long userId) {
        try {
            return R.success(fraudAlertService.listByUserId(userId));
        } catch (Exception e) {
            log.warn("查询用户反诈记录失败, userId={}", userId, e);
            String msg = e.getMessage();
            if (msg != null && msg.contains("Unknown column")) {
                msg = "数据库表缺少列：" + msg;
            }
            return R.fail(500, msg != null ? msg : "查询反诈记录失败");
        }
    }

    /**
     * 查询未处理反诈记录
     */
    @GetMapping("/unhandled")
    public R<List<FraudAlert>> unhandled() {
        try {
            return R.success(fraudAlertService.listUnhandled());
        } catch (Exception e) {
            log.warn("查询未处理反诈记录失败", e);
            String msg = e.getMessage();
            if (msg != null && msg.contains("Unknown column")) {
                msg = "数据库表缺少列：" + msg;
            }
            return R.fail(500, msg != null ? msg : "查询未处理反诈记录失败");
        }
    }

    /**
     * 清空用户反诈记录
     */
    @DeleteMapping("/clear/{userId}")
    public R<Void> clearByUserId(@PathVariable Long userId) {
        try {
            fraudAlertService.clearByUserId(userId);
            return R.success("已清空");
        } catch (Exception e) {
            log.warn("清空反诈记录失败, userId={}", userId, e);
            return R.fail(500, "清空失败：" + e.getMessage());
        }
    }
}

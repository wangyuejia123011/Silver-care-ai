package com.elderly.controller;

import com.elderly.common.R;
import com.elderly.entity.ElderlyUser;
import com.elderly.entity.HealthRecord;
import com.elderly.service.DailyCareService;
import com.elderly.service.ElderlyUserService;
import com.elderly.service.HealthRecordService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 健康记录接口 —— 记录老人健康指标 + AI 关怀
 *
 *  v2.1 新增：
 *   - GET /api/health/today-care/{userId}  首页"今日守护"卡片接口
 *   - GET /api/health/recent/{userId}       老人近 N 条健康记录（趋势分析）
 *   - GET /api/health/today/{userId}        老人今日健康记录
 */
@Slf4j
@RestController
@RequestMapping("/api/health")
public class HealthController {

    @Resource
    private HealthRecordService healthRecordService;
    @Resource
    private ElderlyUserService elderlyUserService;
    @Resource
    private DailyCareService dailyCareService;

    /**
     * 保存健康记录（自动生成AI建议、高危判断）
     */
    @PostMapping("/save")
    public R<HealthRecord> save(@RequestBody HealthRecord record) {
        if (record == null || record.getUserId() == null) {
            return R.fail(400, "用户ID不能为空");
        }
        try {
            HealthRecord saved = healthRecordService.save(record);
            return R.success("保存成功", saved);
        } catch (Exception e) {
            log.error("手动保存健康记录异常, userId={}: {}", record.getUserId(), e.getMessage(), e);
            return R.fail(500, "保存失败：" + e.getMessage());
        }
    }

    /**
     * 查询用户健康记录列表
     */
    @GetMapping("/list/{userId}")
    public R<List<HealthRecord>> list(@PathVariable Long userId) {
        try {
            return R.success(healthRecordService.listByUserId(userId));
        } catch (Exception e) {
            log.error("查询健康记录列表异常, userId={}: {}", userId, e.getMessage(), e);
            return R.fail(500, "查询失败：" + e.getMessage());
        }
    }

    /**
     * 查询用户最近一条健康记录
     */
    @GetMapping("/latest/{userId}")
    public R<HealthRecord> latest(@PathVariable Long userId) {
        try {
            HealthRecord record = healthRecordService.getLatest(userId);
            if (record == null) {
                return R.fail(404, "暂无健康记录");
            }
            return R.success(record);
        } catch (Exception e) {
            log.error("查询最近健康记录异常, userId={}: {}", userId, e.getMessage(), e);
            return R.fail(500, "查询失败：" + e.getMessage());
        }
    }

    @GetMapping("/{id}")
    public R<HealthRecord> getById(@PathVariable Long id) {
        HealthRecord record = healthRecordService.getById(id);
        if (record == null) {
            return R.fail(404, "健康记录不存在");
        }
        return R.success(record);
    }

    /**
     * 「今日守护」AI 关怀：综合今日/近期健康数据 + 档案 + LLM，输出结构化文案。
     * 默认 30 分钟内不重复调用 LLM（Redis/内存双层缓存）。
     *
     * @param userId 老人用户 ID
     * @param forceRefresh true 时忽略缓存强制重算（前端"换个说法"按钮可使用）
     */
    @GetMapping("/today-care/{userId}")
    public R<Map<String, Object>> todayCare(@PathVariable Long userId,
                                            @RequestParam(value = "forceRefresh", required = false) Boolean forceRefresh) {
        Map<String, Object> care;
        String userName = null;
        try {
            ElderlyUser user = elderlyUserService.getById(userId);
            if (user == null) {
                return R.fail(404, "老人档案不存在");
            }
            userName = user.getName();
            List<HealthRecord> today = healthRecordService.listTodayByUserId(userId);
            List<HealthRecord> recent = healthRecordService.listRecentByUserId(userId, 7);
            care = dailyCareService.generate(user, today, recent, LocalDate.now(), Boolean.TRUE.equals(forceRefresh));
        } catch (Exception e) {
            // 任何异常都不应让首页空白：返回兜底关怀文案，并记录日志便于排查
            log.warn("今日守护生成异常，userId={}，返回兜底文案：{}", userId, e.getMessage(), e);
            care = buildDefaultCare(userName);
        }
        if (Boolean.TRUE.equals(forceRefresh)) {
            care.put("fromCache", false);
            care.put("forceRefreshed", true);
        }
        return R.success("ok", care);
    }

    /** 首页今日守护兜底文案：保证老人端永远有内容展示 */
    private Map<String, Object> buildDefaultCare(String name) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        String n = name == null ? "老人家" : name;
        m.put("text", n + "，今天请记得按时服药、适量饮水，保持好心情。如有头晕、胸闷等不舒服，马上联系家人或护工。");
        m.put("bullets", java.util.Arrays.asList("按时服药", "适量饮水", "不适及时联系家人"));
        m.put("medicationReminders", java.util.Collections.emptyList());
        m.put("weatherTip", "温馨提示：今天也要照顾好自己哦。");
        m.put("medicationTip", "按时服药，不漏服、不多服。");
        m.put("source", "银龄智护每日关怀");
        m.put("fromCache", false);
        m.put("fallback", true);
        return m;
    }

    /** 老人今日全部健康记录 */
    @GetMapping("/today/{userId}")
    public R<List<HealthRecord>> todayList(@PathVariable Long userId) {
        try {
            List<HealthRecord> list = healthRecordService.listTodayByUserId(userId);
            return R.success(list == null ? Collections.emptyList() : list);
        } catch (Exception e) {
            log.error("查询今日健康记录异常, userId={}: {}", userId, e.getMessage(), e);
            return R.fail(500, "查询失败：" + e.getMessage());
        }
    }

    /** 老人最近 N 条健康记录（默认 7） */
    @GetMapping("/recent/{userId}")
    public R<List<HealthRecord>> recent(@PathVariable Long userId,
                                        @RequestParam(value = "limit", defaultValue = "7") int limit) {
        if (limit < 1) limit = 7;
        if (limit > 50) limit = 50;
        try {
            return R.success(healthRecordService.listRecentByUserId(userId, limit));
        } catch (Exception e) {
            log.error("查询近期健康记录异常, userId={}: {}", userId, e.getMessage(), e);
            return R.fail(500, "查询失败：" + e.getMessage());
        }
    }

    /**
     * 清空用户健康记录
     */
    @DeleteMapping("/clear/{userId}")
    public R<Void> clearByUserId(@PathVariable Long userId) {
        try {
            healthRecordService.clearByUserId(userId);
            return R.success("已清空");
        } catch (Exception e) {
            log.error("清空健康记录异常, userId={}: {}", userId, e.getMessage(), e);
            return R.fail(500, "清空失败：" + e.getMessage());
        }
    }
}

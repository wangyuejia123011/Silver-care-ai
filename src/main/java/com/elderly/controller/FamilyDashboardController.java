package com.elderly.controller;

import com.elderly.common.R;
import com.elderly.entity.ElderlyUser;
import com.elderly.entity.FamilyBind;
import com.elderly.entity.HealthRecord;
import com.elderly.entity.MedicationReminder;
import com.elderly.entity.MedicationTakenLog;
import com.elderly.mapper.FamilyBindMapper;
import com.elderly.service.HealthRecordService;
import com.elderly.service.MedicationReminderService;
import com.elderly.service.MedicationTakenLogService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 家属端看板 —— 让家属在自己手机上就能帮老人"打卡吃药 / 录入数据 / 看健康快照"。
 *
 * 身份识别：家属用自己的微信 openId 进来，凭 family_bind.open_id 反查他关注的老人，
 * 不新增账号体系（老人仍是 elderly_user，家属是绑定关系上的一方）。
 *
 * 安全：所有写操作都先校验 openId 与 elderlyUserId 的绑定关系，防止越权代操作他人。
 */
@RestController
@RequestMapping("/api/family")
public class FamilyDashboardController {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    @Resource
    private FamilyBindMapper familyBindMapper;
    @Resource
    private HealthRecordService healthRecordService;
    @Resource
    private MedicationReminderService medicationReminderService;
    @Resource
    private MedicationTakenLogService medicationTakenLogService;

    /**
     * 家属看板：返回该家属关注的老人列表，每项含健康快照 + 今日服药计划（含是否已打卡）。
     */
    @GetMapping("/dashboard")
    public R<List<Map<String, Object>>> dashboard(@RequestParam String openId) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (openId == null || openId.isBlank()) return R.success(result);

        List<FamilyBind> binds = familyBindMapper.selectListByOpenId(openId);
        if (binds == null) return R.success(result);

        LocalDate today = LocalDate.now();
        for (FamilyBind b : binds) {
            if (b == null || b.getElderlyUserId() == null) continue;
            Long uid = b.getElderlyUserId();

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("elderlyUserId", uid);
            item.put("name", b.getElderlyName() == null ? "家中老人" : b.getElderlyName());
            item.put("address", b.getElderlyAddress() == null ? "" : b.getElderlyAddress());

            // 最近一条健康记录（快照）
            HealthRecord latest = null;
            try {
                latest = healthRecordService.getLatest(uid);
            } catch (Exception ignored) {
            }
            item.put("latest", latest);

            // 今日服药计划 + 是否已打卡
            List<Map<String, Object>> meds = new ArrayList<>();
            try {
                List<MedicationReminder> list = medicationReminderService.listEnabledByUserId(uid);
                if (list != null) {
                    for (MedicationReminder m : list) {
                        if (m == null || m.getDoseTime() == null) continue;
                        Map<String, Object> md = new LinkedHashMap<>();
                        md.put("time", m.getDoseTime().format(TIME_FMT));
                        md.put("drug", m.getDrugName() == null ? "" : m.getDrugName());
                        md.put("dosage", m.getDosage() == null ? "" : m.getDosage());
                        md.put("taken", medicationTakenLogService.isTaken(
                                uid, m.getDoseTime(), m.getDrugName() == null ? "" : m.getDrugName(), today));
                        meds.add(md);
                    }
                }
            } catch (Exception ignored) {
            }
            item.put("medications", meds);
            result.add(item);
        }
        return R.success(result);
    }

    /** 家属代打卡：确认老人已服药 */
    @PostMapping("/medication/taken")
    public R<MedicationTakenLog> markTaken(@RequestBody ActionReq req) {
        if (!checkBind(req.openId, req.elderlyUserId)) {
            return R.fail(403, "您没有该老人的操作权限");
        }
        LocalTime t = parseTime(req.doseTime);
        if (t == null) return R.fail("服药时间格式不正确");
        MedicationTakenLog log = medicationTakenLogService.markTaken(
                req.elderlyUserId, t, req.drugName, "family");
        return R.success("已帮家人打卡", log);
    }

    /** 家属代录入健康数据（老人不方便说话时，家属帮忙填） */
    @PostMapping("/health/save")
    public R<HealthRecord> saveHealth(@RequestBody ActionReq req) {
        if (!checkBind(req.openId, req.elderlyUserId)) {
            return R.fail(403, "您没有该老人的操作权限");
        }
        HealthRecord record = new HealthRecord();
        record.setUserId(req.elderlyUserId);
        record.setSystolicPressure(req.systolicPressure);
        record.setDiastolicPressure(req.diastolicPressure);
        record.setHeartRate(req.heartRate);
        record.setBloodSugar(req.bloodSugar);
        record.setTemperature(req.temperature);
        record.setSource("manual");
        record.setVoiceText(req.note == null ? null : req.note.trim());
        try {
            HealthRecord saved = healthRecordService.save(record);
            return R.success("已帮家人记录", saved);
        } catch (Exception e) {
            return R.fail(500, "保存失败：" + e.getMessage());
        }
    }

    /** 校验：该 openId 是否是这个老人的家属 */
    private boolean checkBind(String openId, Long elderlyUserId) {
        if (openId == null || openId.isBlank() || elderlyUserId == null) return false;
        try {
            List<FamilyBind> binds = familyBindMapper.selectListByOpenId(openId);
            if (binds == null) return false;
            for (FamilyBind b : binds) {
                if (b != null && elderlyUserId.equals(b.getElderlyUserId())) return true;
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    private LocalTime parseTime(String s) {
        if (s == null || s.isBlank()) return null;
        s = s.trim();
        DateTimeFormatter[] fmts = {
                DateTimeFormatter.ofPattern("HH:mm"),
                DateTimeFormatter.ofPattern("HH:mm:ss")
        };
        for (DateTimeFormatter f : fmts) {
            try {
                return LocalTime.parse(s, f);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** 家属端写操作请求体 */
    public static class ActionReq {
        /** 家属自己的微信 openId */
        public String openId;
        /** 被操作的老人ID */
        public Long elderlyUserId;
        /** 服药时间 HH:mm */
        public String doseTime;
        /** 药名 */
        public String drugName;
        /** 备注 */
        public String note;
        public Integer systolicPressure;
        public Integer diastolicPressure;
        public Integer heartRate;
        public Double bloodSugar;
        public Double temperature;
    }
}

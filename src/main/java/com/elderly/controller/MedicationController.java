package com.elderly.controller;

import com.elderly.common.R;
import com.elderly.entity.MedicationReminder;
import com.elderly.service.MedicationReminderService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@RestController
@RequestMapping("/api/medication")
public class MedicationController {

    @Resource
    private MedicationReminderService medicationReminderService;

    /** 查询某老人的服药计划 */
    @GetMapping("/list")
    public R<List<MedicationReminder>> list(@RequestParam Long userId) {
        return R.success(medicationReminderService.listByUserId(userId));
    }

    /** 新增服药计划 */
    @PostMapping("/save")
    public R<MedicationReminder> save(@RequestBody MedReq req) {
        if (req.userId == null) return R.fail("用户ID不能为空");
        if (req.drugName == null || req.drugName.isBlank()) return R.fail("药名不能为空");
        if (req.doseTime == null || req.doseTime.isBlank()) return R.fail("服药时间不能为空");
        MedicationReminder r = new MedicationReminder();
        r.setUserId(req.userId);
        r.setDrugName(req.drugName.trim());
        r.setDoseTime(parseTime(req.doseTime));
        r.setDosage(req.dosage == null ? "" : req.dosage.trim());
        r.setNote(req.note == null ? "" : req.note.trim());
        r.setEnabled(req.enabled == null ? 1 : req.enabled);
        return R.success("保存成功", medicationReminderService.save(r));
    }

    /** 更新服药计划 */
    @PutMapping("/update")
    public R<Boolean> update(@RequestBody MedReq req) {
        if (req.id == null) return R.fail("ID不能为空");
        MedicationReminder r = new MedicationReminder();
        r.setId(req.id);
        r.setUserId(req.userId);
        r.setDrugName(req.drugName);
        r.setDoseTime(parseTime(req.doseTime));
        r.setDosage(req.dosage);
        r.setNote(req.note);
        r.setEnabled(req.enabled);
        return R.success(medicationReminderService.update(r));
    }

    /** 删除服药计划 */
    @DeleteMapping("/delete")
    public R<Boolean> delete(@RequestParam Long id) {
        return R.success(medicationReminderService.delete(id));
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
        return LocalTime.parse(s);
    }

    /** 请求体（doseTime 用字符串传入，便于前端调用） */
    public static class MedReq {
        public Long id;
        public Long userId;
        public String drugName;
        public String doseTime;
        public String dosage;
        public String note;
        public Integer enabled;
    }
}

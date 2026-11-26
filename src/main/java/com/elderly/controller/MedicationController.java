package com.elderly.controller;

import com.elderly.common.R;
import com.elderly.entity.MedicationReminder;
import com.elderly.entity.MedicationTakenLog;
import com.elderly.service.MedicationReminderService;
import com.elderly.service.MedicationTakenLogService;
import com.elderly.task.MedicationMissedTask;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@RestController
@RequestMapping("/api/medication")
public class MedicationController {

    @Resource
    private MedicationReminderService medicationReminderService;

    @Resource
    private MedicationTakenLogService medicationTakenLogService;

    @Resource
    private MedicationMissedTask medicationMissedTask;

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

    /** 打卡：老人确认已服药（供"漏服提醒家属"判定用） */
    @PostMapping("/taken")
    public R<MedicationTakenLog> markTaken(@RequestBody TakenReq req) {
        if (req.userId == null) return R.fail("用户ID不能为空");
        LocalTime t = parseTime(req.doseTime);
        if (t == null) return R.fail("服药时间格式不正确");
        MedicationTakenLog log = medicationTakenLogService.markTaken(req.userId, t, req.drugName, req.source);
        return R.success("已记录服药", log);
    }

    /** 取消打卡（误点或家属撤销） */
    @DeleteMapping("/taken")
    public R<Boolean> cancelTaken(@RequestParam Long userId,
                                   @RequestParam String doseTime,
                                   @RequestParam(required = false) String drugName) {
        LocalTime t = parseTime(doseTime);
        if (t == null) return R.fail("服药时间格式不正确");
        return R.success("已取消", medicationTakenLogService.cancelTaken(userId, t, drugName, null));
    }

    /** 查询某老人某天的服药打卡记录 */
    @GetMapping("/taken/list")
    public R<List<MedicationTakenLog>> takenList(@RequestParam Long userId,
                                                  @RequestParam(required = false) String date) {
        LocalDate d = null;
        if (date != null && !date.isBlank()) {
            try {
                d = LocalDate.parse(date.trim());
            } catch (Exception ignored) {
                d = LocalDate.now();
            }
        }
        return R.success(medicationTakenLogService.listByUserIdAndDate(userId, d));
    }

    /** 手动触发一次漏服巡检（运维/联调用，便于立刻验证效果） */
    @PostMapping("/missed/check")
    public R<Integer> checkMissed() {
        return R.success("巡检完成", medicationMissedTask.runCheck());
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

    /** 服药打卡请求体 */
    public static class TakenReq {
        public Long userId;
        /** 服药时间，格式 HH:mm 或 HH:mm:ss */
        public String doseTime;
        public String drugName;
        /** 打卡来源：elder=老人自己 / family=家属代填 */
        public String source;
    }
}

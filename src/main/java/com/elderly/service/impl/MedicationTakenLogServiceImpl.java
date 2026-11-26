package com.elderly.service.impl;

import com.elderly.entity.MedicationTakenLog;
import com.elderly.mapper.MedicationTakenLogMapper;
import com.elderly.service.MedicationTakenLogService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Service
public class MedicationTakenLogServiceImpl implements MedicationTakenLogService {

    @Resource
    private MedicationTakenLogMapper takenLogMapper;

    @Override
    @Transactional
    public MedicationTakenLog markTaken(Long userId, LocalTime doseTime, String drugName, String source) {
        if (userId == null || doseTime == null) return null;
        LocalDate today = LocalDate.now();
        String drug = drugName == null ? "" : drugName.trim();

        // 幂等：已打过卡就直接返回旧记录，不再插第二条
        if (takenLogMapper.countByKey(userId, doseTime, drug, today) > 0) {
            List<MedicationTakenLog> list = takenLogMapper.selectByUserIdAndDate(userId, today);
            for (MedicationTakenLog l : list) {
                if (l.getDoseTime() != null
                        && l.getDoseTime().equals(doseTime)
                        && (l.getDrugName() == null ? "" : l.getDrugName()).equals(drug)) {
                    return l;
                }
            }
        }

        MedicationTakenLog log = new MedicationTakenLog();
        log.setUserId(userId);
        log.setDoseTime(doseTime);
        log.setDrugName(drug);
        log.setTakenDate(today);
        log.setTakenAt(LocalDateTime.now());
        log.setSource(source == null || source.isBlank() ? "elder" : source.trim());
        takenLogMapper.insert(log);
        return log;
    }

    @Override
    public boolean cancelTaken(Long userId, LocalTime doseTime, String drugName, LocalDate date) {
        if (userId == null || doseTime == null) return false;
        LocalDate day = date == null ? LocalDate.now() : date;
        return takenLogMapper.deleteByKey(userId, doseTime, drugName == null ? "" : drugName.trim(), day) >= 0;
    }

    @Override
    public boolean isTaken(Long userId, LocalTime doseTime, String drugName, LocalDate date) {
        if (userId == null || doseTime == null) return false;
        LocalDate day = date == null ? LocalDate.now() : date;
        return takenLogMapper.countByKey(userId, doseTime, drugName == null ? "" : drugName.trim(), day) > 0;
    }

    @Override
    public List<MedicationTakenLog> listByUserIdAndDate(Long userId, LocalDate date) {
        if (userId == null) return List.of();
        return takenLogMapper.selectByUserIdAndDate(userId, date == null ? LocalDate.now() : date);
    }
}

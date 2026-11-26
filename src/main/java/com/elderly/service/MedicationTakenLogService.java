package com.elderly.service;

import com.elderly.entity.MedicationTakenLog;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public interface MedicationTakenLogService {

    /**
     * 老人打勾确认已服药（幂等：同一天同一条药重复打勾只保留一条）。
     */
    MedicationTakenLog markTaken(Long userId, LocalTime doseTime, String drugName, String source);

    /**
     * 取消打勾（老人误点或家属撤销）。
     */
    boolean cancelTaken(Long userId, LocalTime doseTime, String drugName, LocalDate date);

    /**
     * 判断某条药当天是否已打卡。
     */
    boolean isTaken(Long userId, LocalTime doseTime, String drugName, LocalDate date);

    /**
     * 查某老人某天的全部打卡记录。
     */
    List<MedicationTakenLog> listByUserIdAndDate(Long userId, LocalDate date);
}

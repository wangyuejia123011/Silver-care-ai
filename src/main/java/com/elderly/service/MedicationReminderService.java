package com.elderly.service;

import com.elderly.entity.MedicationReminder;
import java.util.List;

public interface MedicationReminderService {

    /** 某老人的全部服药计划（按时间升序） */
    List<MedicationReminder> listByUserId(Long userId);

    /** 某老人已启用的服药计划（按时间升序） */
    List<MedicationReminder> listEnabledByUserId(Long userId);

    /** 新增 */
    MedicationReminder save(MedicationReminder reminder);

    /** 更新 */
    boolean update(MedicationReminder reminder);

    /** 删除 */
    boolean delete(Long id);
}

package com.elderly.service.impl;

import com.elderly.entity.MedicationReminder;
import com.elderly.mapper.MedicationReminderMapper;
import com.elderly.service.MedicationReminderService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MedicationReminderServiceImpl implements MedicationReminderService {

    @Resource
    private MedicationReminderMapper reminderMapper;

    @Override
    public List<MedicationReminder> listByUserId(Long userId) {
        if (userId == null) return List.of();
        return reminderMapper.selectByUserId(userId);
    }

    @Override
    public List<MedicationReminder> listEnabledByUserId(Long userId) {
        if (userId == null) return List.of();
        return reminderMapper.selectEnabledByUserId(userId);
    }

    @Override
    public MedicationReminder save(MedicationReminder reminder) {
        if (reminder.getEnabled() == null) reminder.setEnabled(1);
        reminderMapper.insert(reminder);
        return reminder;
    }

    @Override
    public boolean update(MedicationReminder reminder) {
        if (reminder == null || reminder.getId() == null) return false;
        return reminderMapper.update(reminder) > 0;
    }

    @Override
    public boolean delete(Long id) {
        if (id == null) return false;
        return reminderMapper.deleteById(id) > 0;
    }
}

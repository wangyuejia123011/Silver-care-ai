package com.elderly.service.impl;

import com.elderly.entity.FraudAlert;
import com.elderly.mapper.FraudAlertMapper;
import com.elderly.service.FraudAlertService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class FraudAlertServiceImpl implements FraudAlertService {

    @Resource
    private FraudAlertMapper fraudAlertMapper;

    @Override
    public FraudAlert save(FraudAlert alert) {
        fraudAlertMapper.insert(alert);
        return alert;
    }

    @Override
    public List<FraudAlert> listByUserId(Long userId) {
        return fraudAlertMapper.selectByUserId(userId);
    }

    @Override
    public int countByTimeRange(LocalDateTime start, LocalDateTime end) {
        return fraudAlertMapper.countByTimeRange(start, end);
    }

    @Override
    public List<FraudAlert> listUnhandled() {
        return fraudAlertMapper.selectUnhandled();
    }

    @Override
    public int clearByUserId(Long userId) {
        return fraudAlertMapper.deleteByUserId(userId);
    }
}

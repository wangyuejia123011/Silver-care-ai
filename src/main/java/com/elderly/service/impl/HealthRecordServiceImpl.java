package com.elderly.service.impl;

import com.elderly.entity.HealthRecord;
import com.elderly.mapper.HealthRecordMapper;
import com.elderly.service.HealthRecordService;
import com.elderly.util.HealthRiskEvaluator;
import com.elderly.util.LlmUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class HealthRecordServiceImpl implements HealthRecordService {

    private static final Logger log = LoggerFactory.getLogger(HealthRecordServiceImpl.class);

    @Resource
    private HealthRecordMapper healthRecordMapper;
    @Resource
    private LlmUtil llmUtil;

    @Override
    public HealthRecord saveRaw(HealthRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("健康记录不能为空");
        }
        if (record.getIsAlert() == null) {
            record.setIsAlert(0);
        }
        if (record.getRiskLevel() == null) {
            record.setRiskLevel("low");
        }
        if (record.getSource() == null) {
            record.setSource("voice");
        }
        try {
            healthRecordMapper.insert(record);
            log.info("健康记录已落库, userId={}, isAlert={}", record.getUserId(), record.getIsAlert());
            return record;
        } catch (Exception e) {
            log.error("健康记录落库失败, userId={}, msg={}", record.getUserId(), e.getMessage(), e);
            throw new RuntimeException("健康记录保存失败：" + e.getMessage(), e);
        }
    }

    @Override
    public HealthRecord save(HealthRecord record) {
        // 统一使用 HealthRiskEvaluator 评估风险等级和高危标记
        boolean isAlert = HealthRiskEvaluator.isHighRisk(record);
        record.setIsAlert(isAlert ? 1 : 0);
        if (record.getRiskLevel() == null) {
            record.setRiskLevel(HealthRiskEvaluator.evaluateRisk(record));
        }

        // AI生成健康建议（高危时给固定提示，非高危时调用LLM）
        if (isAlert) {
            record.setAiAdvice(HealthRiskEvaluator.buildHighRiskWarning(record));
        } else {
            try {
                String prompt = String.format(
                        "老人血压%s/%s，心率%s，血糖%s，请用50字以内给出口语化的健康建议。",
                        safeInt(record.getSystolicPressure()), safeInt(record.getDiastolicPressure()),
                        safeInt(record.getHeartRate()), safeDouble(record.getBloodSugar())
                );
                String advice = llmUtil.chatSync(prompt);
                record.setAiAdvice(advice == null || advice.isBlank()
                        ? "注意规律作息，清淡饮食，适量运动。" : advice.trim());
            } catch (Exception e) {
                log.warn("AI建议生成失败: {}", e.getMessage());
                record.setAiAdvice("注意规律作息，清淡饮食，适量运动。");
            }
        }

        healthRecordMapper.insert(record);
        log.info("健康记录已保存, userId={}, isAlert={}", record.getUserId(), isAlert);
        return record;
    }

    @Override
    public List<HealthRecord> listByUserId(Long userId) {
        return healthRecordMapper.selectByUserId(userId);
    }

    @Override
    public HealthRecord getLatest(Long userId) {
        return healthRecordMapper.selectLatestByUserId(userId);
    }

    @Override
    public List<HealthRecord> listRecentByUserId(Long userId, int limit) {
        if (userId == null || limit <= 0) return java.util.Collections.emptyList();
        return healthRecordMapper.selectRecentByUserId(userId, limit);
    }

    @Override
    public List<HealthRecord> listTodayByUserId(Long userId) {
        if (userId == null) return java.util.Collections.emptyList();
        LocalDateTime start = LocalDateTime.now().toLocalDate().atStartOfDay();
        LocalDateTime end = start.plusDays(1).minusNanos(1);
        return healthRecordMapper.selectTodayByUserId(userId, start, end);
    }

    @Override
    public HealthRecord getById(Long id) {
        return healthRecordMapper.selectById(id);
    }

    @Override
    public List<HealthRecord> listByTimeRange(LocalDateTime start, LocalDateTime end) {
        return healthRecordMapper.selectByTimeRange(start, end);
    }

    private static String safeInt(Integer value) {
        return value == null ? "未测" : value.toString();
    }

    private static String safeDouble(Double value) {
        return value == null ? "未测" : String.format("%.1f", value);
    }

    @Override
    public int countByTimeRange(LocalDateTime start, LocalDateTime end) {
        return healthRecordMapper.countByTimeRange(start, end);
    }

    @Override
    public int countHighRisk(LocalDateTime start, LocalDateTime end) {
        return healthRecordMapper.countHighRiskByTimeRange(start, end);
    }

    @Override
    public int clearByUserId(Long userId) {
        return healthRecordMapper.deleteByUserId(userId);
    }

    @Override
    public int updateAiAdviceById(Long id, String aiAdvice) {
        return healthRecordMapper.updateAiAdviceById(id, aiAdvice);
    }
}

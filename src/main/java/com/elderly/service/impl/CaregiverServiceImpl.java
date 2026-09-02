package com.elderly.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.elderly.entity.Caregiver;
import com.elderly.mapper.CaregiverMapper;
import com.elderly.service.CaregiverService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;

@Service
public class CaregiverServiceImpl implements CaregiverService {

    private static final Logger log = LoggerFactory.getLogger(CaregiverServiceImpl.class);

    @Resource
    private CaregiverMapper caregiverMapper;

    @Override
    public List<Caregiver> listAll() {
        return caregiverMapper.selectAll();
    }

    @Override
    public Caregiver getById(Long id) {
        return caregiverMapper.selectById(id);
    }

    /**
     * 加权匹配算法（文档2.0核心算法）：
     * 第一步 技能过滤：筛选 skills 包含所需技能的护工集合A；
     * 第二步 区域过滤：在A中优先筛选 area 与老人地址匹配的护工集合B（无匹配则回退到A）；
     * 第三步 负载均衡：按 current_order_count 升序排列，取负载最小的第一名。
     */
    @Override
    public Caregiver matchBestCaregiver(String skill, String address) {
        // 第一步：技能过滤（无技能要求时取全部在岗护工）
        List<Caregiver> candidates = StringUtils.hasText(skill)
                ? caregiverMapper.selectBySkill(skill)
                : caregiverMapper.selectAll();

        if (candidates == null || candidates.isEmpty()) {
            // 技能过滤后无人可选，回退到全部在岗护工（不能让老人叫不到人）
            log.warn("技能[{}]无可上岗护工，回退全量在岗护工", skill);
            candidates = caregiverMapper.selectAll();
        }
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        // 第二步：区域过滤（护工的负责区域出现在老人地址中，或老人地址包含区域名）
        List<Caregiver> areaMatched = candidates.stream()
                .filter(c -> c.getArea() != null && address != null
                        && (address.contains(c.getArea()) || c.getArea().contains("全部")))
                .toList();
        List<Caregiver> pool = areaMatched.isEmpty() ? candidates : areaMatched;
        log.info("加权调度：技能过滤后{}人，区域过滤后{}人", candidates.size(), pool.size());

        // 第三步：负载均衡（当前接单数升序，取第一名）
        return pool.stream()
                .min(Comparator.comparingInt(c -> c.getCurrentOrderCount() == null ? 0 : c.getCurrentOrderCount()))
                .orElse(null);
    }

    /** 解析护工skills JSON字符串 */
    public static List<String> parseSkills(Caregiver caregiver) {
        if (caregiver == null || caregiver.getSkills() == null) {
            return List.of();
        }
        try {
            JSONArray arr = JSON.parseArray(caregiver.getSkills());
            return arr.toList(String.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    public List<Caregiver> listTopByTotal(int limit) {
        return caregiverMapper.selectTopByTotal(limit);
    }

    @Override
    public Caregiver addCaregiver(Caregiver caregiver) {
        if (caregiver.getSkills() != null && !caregiver.getSkills().isBlank()
                && !caregiver.getSkills().trim().startsWith("[")) {
            // 允许前端传逗号分隔的技能，自动转为JSON数组
            String[] parts = caregiver.getSkills().split("[,，]");
            JSONArray arr = new JSONArray();
            for (String p : parts) {
                if (!p.isBlank()) arr.add(p.trim());
            }
            caregiver.setSkills(arr.toJSONString());
        }
        caregiverMapper.insert(caregiver);
        return caregiver;
    }
}

package com.elderly.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.elderly.dto.CaregiverProfile;
import com.elderly.entity.CareOrder;
import com.elderly.entity.Caregiver;
import com.elderly.entity.DispatchContext;
import com.elderly.mapper.CareOrderMapper;
import com.elderly.mapper.CaregiverMapper;
import com.elderly.service.CaregiverService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class CaregiverServiceImpl implements CaregiverService {

    private static final Logger log = LoggerFactory.getLogger(CaregiverServiceImpl.class);

    @Resource
    private CaregiverMapper caregiverMapper;

    @Resource
    private CareOrderMapper careOrderMapper;

    @Override
    public List<Caregiver> listAll() {
        return caregiverMapper.selectAll();
    }

    @Override
    public Caregiver getById(Long id) {
        return caregiverMapper.selectById(id);
    }

    /**
     * 护工详情：先查扩展档案（avatar/bio/评分等），
     * 若线上库还没跑升级 SQL 导致字段不存在，自动降级成基础档案，页面不至于白屏。
     */
    @Override
    public CaregiverProfile getProfile(Long id) {
        Caregiver caregiver = null;
        try {
            caregiver = caregiverMapper.selectProfileById(id);
        } catch (Exception e) {
            log.warn("护工[{}]扩展字段查询失败，降级为基础档案：{}", id, e.getMessage());
            caregiver = caregiverMapper.selectById(id);
        }
        if (caregiver == null) {
            return null;
        }

        CaregiverProfile profile = new CaregiverProfile();
        profile.setCaregiver(caregiver);
        profile.setSkills(parseSkills(caregiver));
        profile.setStatusText("on".equals(caregiver.getStatus()) ? "在岗接单中" : "休息中");

        Map<String, Object> stats = new HashMap<>();
        stats.put("todayOrders", countSafe(() -> careOrderMapper.countTodayByCaregiverId(id)));
        stats.put("pendingOrders", countSafe(() -> careOrderMapper.countStatusByCaregiverId(id, "assigned")));
        stats.put("doneOrders", countSafe(() -> careOrderMapper.countStatusByCaregiverId(id, "done")));
        stats.put("totalOrders", caregiver.getTotalOrderCount() == null ? 0 : caregiver.getTotalOrderCount());
        stats.put("currentOrders", caregiver.getCurrentOrderCount() == null ? 0 : caregiver.getCurrentOrderCount());
        try {
            List<CareOrder> orders = careOrderMapper.selectElderlyByCaregiverId(id, 6);
            stats.put("elderlyNames", orders.stream()
                    .map(CareOrder::getElderlyName)
                    .filter(StringUtils::hasText)
                    .toList());
        } catch (Exception e) {
            stats.put("elderlyNames", List.of());
        }
        profile.setStats(stats);
        return profile;
    }

    /** 统计计数兜底：出任何问题都按 0 处理，不能让详情页挂掉 */
    private int countSafe(java.util.function.Supplier<Integer> supplier) {
        try {
            Integer n = supplier.get();
            return n == null ? 0 : n;
        } catch (Exception e) {
            log.warn("护工统计查询失败：{}", e.getMessage());
            return 0;
        }
    }

    @Override
    public void updateCaregiver(Caregiver caregiver) {
        if (caregiver == null || caregiver.getId() == null) {
            return;
        }
        if (StringUtils.hasText(caregiver.getSkills())
                && !caregiver.getSkills().trim().startsWith("[")) {
            // 允许前端传逗号分隔的技能，自动转为JSON数组
            String[] parts = caregiver.getSkills().split("[,，]");
            JSONArray arr = new JSONArray();
            for (String p : parts) {
                if (!p.isBlank()) arr.add(p.trim());
            }
            caregiver.setSkills(arr.toJSONString());
        }
        caregiverMapper.updateProfile(caregiver);
    }

    /**
     * 场景化加权匹配算法（Agent5 调度核心）：
     * 在"在岗护工"集合中，按以下维度逐人打分，取总分最高者：
     *   1) 技能匹配（所需技能属于该护工技能标签）→ +40；
     *   2) 性别匹配（助浴等场景要求与老人同性别）→ +80；
     *   3) 医疗设备匹配（健康类要求携带设备，能带设备 → +100，不能带 → -200 重罚）；
     *   4) 区域/距离匹配（护工负责区域命中老人地址，作为距离远近的代理）→ +60；
     *   5) 负载均衡（当前接单数越低越好）→ 减去 currentOrderCount。
     * 某维度无要求（如日常照料无需设备、无需性别）则该项不计分。
     * 始终保证"叫得到人"：即使无人完全满足，也会返回分最高者。
     */
    @Override
    public Caregiver matchBestCaregiver(DispatchContext ctx, String address) {
        List<Caregiver> candidates = caregiverMapper.selectAll(); // 在岗，已按负载升序
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        // 第一步：技能硬过滤（无技能要求则不过滤；无匹配则回退全量，保证叫得到人）
        if (ctx.getSkill() != null && !ctx.getSkill().isBlank()) {
            List<Caregiver> bySkill = candidates.stream()
                    .filter(c -> parseSkills(c).contains(ctx.getSkill()))
                    .toList();
            if (!bySkill.isEmpty()) {
                candidates = bySkill;
            } else {
                log.warn("技能[{}]无可上岗护工，放宽至全量在岗护工", ctx.getSkill());
            }
        }

        // 第二步：加权打分
        Caregiver best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Caregiver c : candidates) {
            int score = 0;

            // 技能匹配
            if (ctx.getSkill() != null && !ctx.getSkill().isBlank()
                    && parseSkills(c).contains(ctx.getSkill())) {
                score += 40;
            }

            // 性别匹配（助浴等需与老人同性别）
            if (ctx.getRequireGender() != null && !ctx.getRequireGender().isBlank()
                    && ctx.getRequireGender().equals(c.getGender())) {
                score += 80;
            }

            // 医疗设备匹配（健康类需携带设备）
            boolean canDevice = c.getCanCarryDevice() != null && c.getCanCarryDevice() == 1;
            if (ctx.isNeedDevice()) {
                if (canDevice) {
                    score += 100;
                } else {
                    score -= 200; // 健康类但无法带设备，重罚
                }
            }

            // 区域/距离匹配（护工负责区域命中老人地址，作为距离远近的代理信号）
            if (c.getArea() != null && address != null
                    && (address.contains(c.getArea()) || c.getArea().contains("全部"))) {
                score += 60;
            }

            // 负载均衡：当前接单数越低越好
            score -= (c.getCurrentOrderCount() == null ? 0 : c.getCurrentOrderCount());

            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }

        if (best != null) {
            log.info("加权调度：场景={}, 候选{}人, 选中={}(技能={}, 性别={}, 带设备={}, 区域={}, 负载={})",
                    ctx.getCategory(), candidates.size(), best.getName(),
                    ctx.getSkill(), best.getGender(),
                    best.getCanCarryDevice(), best.getArea(), best.getCurrentOrderCount());
        }
        return best;
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

package com.elderly.service;

import com.elderly.entity.Caregiver;
import java.util.List;

public interface CaregiverService {

    /** 查询全部在岗护工 */
    List<Caregiver> listAll();

    /** 根据ID查询 */
    Caregiver getById(Long id);

    /**
     * Agent5 加权调度核心算法：技能过滤 → 区域过滤 → 负载均衡
     *
     * @param skill   需要的技能标签（如"康复"），为空则不过滤技能
     * @param address 老人地址（用于区域匹配，护工area包含在地址中优先）
     * @return 最佳护工；无人可选时返回null
     */
    Caregiver matchBestCaregiver(String skill, String address);

    /** 新增护工 */
    Caregiver addCaregiver(Caregiver caregiver);

    /** 累计接单排行（数据看板用） */
    List<Caregiver> listTopByTotal(int limit);
}

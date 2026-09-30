package com.elderly.service;

import com.elderly.entity.Caregiver;
import com.elderly.entity.DispatchContext;
import java.util.List;

public interface CaregiverService {

    /** 查询全部在岗护工 */
    List<Caregiver> listAll();

    /** 根据ID查询 */
    Caregiver getById(Long id);

    /**
     * Agent5 场景化加权调度核心算法：
     * 技能匹配 → 性别匹配(助浴等) → 医疗设备匹配(健康类) → 区域/距离匹配 → 负载均衡
     *
     * @param ctx     场景化派单上下文（含所需技能、是否带设备、要求护工性别、紧急程度）
     * @param address 老人地址（用于区域/距离匹配，护工area出现在地址中优先）
     * @return 最佳护工；无人可选时返回null
     */
    Caregiver matchBestCaregiver(DispatchContext ctx, String address);

    /** 新增护工 */
    Caregiver addCaregiver(Caregiver caregiver);

    /** 累计接单排行（数据看板用） */
    List<Caregiver> listTopByTotal(int limit);
}

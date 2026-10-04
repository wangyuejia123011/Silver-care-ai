package com.elderly.service;

import com.elderly.dto.CaregiverProfile;
import com.elderly.entity.Caregiver;
import com.elderly.entity.DispatchContext;
import java.util.List;

public interface CaregiverService {

    /** 查询全部在岗护工 */
    List<Caregiver> listAll();

    /** 根据ID查询 */
    Caregiver getById(Long id);

    /**
     * 护工详情（详情页面用）：基础档案 + 技能数组 + 服务统计 + 近期服务过的老人。
     * 线上库若还没跑升级 SQL（扩展列不存在），内部自动降级为基础档案，不会抛错。
     */
    CaregiverProfile getProfile(Long id);

    /** 更新护工资料（只更新传入的非空字段） */
    void updateCaregiver(Caregiver caregiver);

    /**
     * Agent5 场景化加权调度核心算法：
     * 技能匹配 → 性别匹配(助浴等) → 医疗设备匹配(健康类) → 区域/距离匹配 → 负载均衡
     *
     * @param ctx     场景化派单上下文（含所需技能、是否带设备、要求护工性别、紧急程度）
     * @param address 老人地址（用于区域/距离匹配，护工area出现在地址中优先）
     * @return 最佳护工；无人可选时返回null
     */
    Caregiver matchBestCaregiver(DispatchContext ctx, String address);

    /** 注销护工账号（物理删除档案，名下历史工单保留） */
    void deleteCaregiver(Long id);

    /** 新增护工 */
    Caregiver addCaregiver(Caregiver caregiver);

    /** 累计接单排行（数据看板用） */
    List<Caregiver> listTopByTotal(int limit);
}

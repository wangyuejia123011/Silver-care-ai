package com.elderly.service;

import com.elderly.entity.FraudAlert;
import java.time.LocalDateTime;
import java.util.List;

public interface FraudAlertService {

    /** 保存反诈预警记录 */
    FraudAlert save(FraudAlert alert);

    /** 查询用户反诈记录 */
    List<FraudAlert> listByUserId(Long userId);

    /** 统计某时间段内反诈拦截数 */
    int countByTimeRange(LocalDateTime start, LocalDateTime end);

    /** 查询未处理记录 */
    List<FraudAlert> listUnhandled();

    /** 清空用户反诈记录 */
    int clearByUserId(Long userId);
}

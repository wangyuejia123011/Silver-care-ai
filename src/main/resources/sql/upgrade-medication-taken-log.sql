-- ============================================================
-- 服药打卡记录表（支撑"到点未服药 → 通知家属"）
--
-- 背景：适老化评审要求"如果到了时间未服药，应触发通知家属的预警"。
-- 之前服药状态只存在小程序本地（silver_med_taken），后端完全不知道老人吃没吃，
-- 家属端也就无法知道是否漏服。这张表把打卡落到服务端，作为漏服巡检的数据源。
--
-- 部署顺序（重要）：先在云托管库执行本脚本建表，再 push + 重新部署后端。
-- 幂等：可重复执行，不会报错。
-- ============================================================

CREATE TABLE IF NOT EXISTS medication_taken_log (
    id          BIGINT       PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    user_id     BIGINT       NOT NULL COMMENT '关联老人用户ID',
    dose_time   TIME         NOT NULL COMMENT '服药时间，如 08:00:00',
    drug_name   VARCHAR(100) NOT NULL DEFAULT '' COMMENT '药名，与 medication_reminder.drug_name 一致',
    taken_date  DATE         NOT NULL COMMENT '打卡日期',
    taken_at    DATETIME     NOT NULL COMMENT '实际打卡时刻',
    source      VARCHAR(20)  DEFAULT 'elder' COMMENT '打卡来源：elder=老人自己打勾 / family=家属代填',
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_user_dose_drug_date (user_id, dose_time, drug_name, taken_date),
    INDEX idx_user_date (user_id, taken_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='老人服药打卡记录表';

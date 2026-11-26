-- ============================================================
-- 服药打卡记录表（纯净执行版 · 供微信云托管 DMC / 网页 SQL 控制台直接粘贴）
--
-- 说明：已移除全部注释，避免部分控制台编码异常。
-- 幂等：可重复执行，不会报错。
-- 执行顺序：本脚本 → 后端 push + 重新部署 → 前端重新编译上传。
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

SELECT 'OK: medication_taken_log 已就绪' AS result;

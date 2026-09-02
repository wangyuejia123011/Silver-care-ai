-- ================================================
-- 银龄智护 · 服药提醒表（升级脚本）
-- 适用：已在运行的 elderly_ai 库执行一次即可
-- ================================================

CREATE TABLE IF NOT EXISTS medication_reminder (
    id          BIGINT       PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    user_id     BIGINT       NOT NULL COMMENT '关联老人用户ID',
    drug_name   VARCHAR(100) NOT NULL COMMENT '药名，如 降压药',
    dose_time   TIME         NOT NULL COMMENT '服药时间，如 15:00:00',
    dosage      VARCHAR(100) DEFAULT '' COMMENT '剂量，如 1片',
    note        VARCHAR(255) DEFAULT '' COMMENT '备注',
    enabled     TINYINT      DEFAULT 1 COMMENT '是否启用：1-是 0-否',
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='老人服药提醒计划表';

-- 演示种子数据：给两位演示老人各添加一份服药计划（用于展示"今日守护"中的具体服药提醒）
INSERT INTO medication_reminder (user_id, drug_name, dose_time, dosage, enabled)
SELECT id, '降压药',  '08:00:00', '1片', 1 FROM elderly_user WHERE open_id = 'demo_openid_001';
INSERT INTO medication_reminder (user_id, drug_name, dose_time, dosage, enabled)
SELECT id, '降糖药',  '12:30:00', '1粒', 1 FROM elderly_user WHERE open_id = 'demo_openid_002';
INSERT INTO medication_reminder (user_id, drug_name, dose_time, dosage, enabled)
SELECT id, '安神药',  '21:00:00', '1片', 1 FROM elderly_user WHERE open_id = 'demo_openid_002';

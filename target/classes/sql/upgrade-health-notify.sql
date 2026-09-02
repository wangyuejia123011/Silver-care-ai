-- ================================================
-- 健康语音上报 + 家属/护工通知 升级脚本
-- 用法: mysql -uroot -p elderly_ai < upgrade-health-notify.sql
-- ================================================
USE elderly_ai;

ALTER TABLE health_record
    ADD COLUMN voice_text TEXT COMMENT '语音识别原文' AFTER source,
    ADD COLUMN risk_level VARCHAR(10) DEFAULT 'low' COMMENT '风险等级：low/medium/high' AFTER voice_text;

CREATE TABLE IF NOT EXISTS family_bind (
    id               BIGINT       PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    elderly_user_id  BIGINT       NOT NULL COMMENT '老人用户ID',
    role             VARCHAR(20)  NOT NULL COMMENT '角色：family/caregiver',
    name             VARCHAR(50)  COMMENT '家属或护工姓名',
    phone            VARCHAR(20)  COMMENT '联系电话',
    open_id          VARCHAR(64)  COMMENT '家属小程序openid（护工可为空）',
    caregiver_id     BIGINT       COMMENT '护工ID（role=caregiver时）',
    notify_enabled   TINYINT      DEFAULT 1 COMMENT '是否接收通知：1-是 0-否',
    create_time      DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_elderly (elderly_user_id),
    INDEX idx_open_id (open_id),
    INDEX idx_caregiver (caregiver_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='老人与家属/护工绑定表';

CREATE TABLE IF NOT EXISTS health_notify (
    id               BIGINT       PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    elderly_user_id  BIGINT       COMMENT '老人用户ID',
    elderly_name     VARCHAR(50)  COMMENT '老人姓名',
    record_id        BIGINT       COMMENT '关联健康记录ID',
    bind_id          BIGINT       COMMENT '关联绑定ID',
    receiver_role    VARCHAR(20)  COMMENT '接收人角色：family/caregiver',
    receiver_name    VARCHAR(50)  COMMENT '接收人姓名',
    receiver_open_id VARCHAR(64)  COMMENT '家属openid',
    caregiver_id     BIGINT       COMMENT '护工ID',
    risk_level       VARCHAR(10)  COMMENT '风险等级：medium/high',
    title            VARCHAR(100) COMMENT '通知标题',
    content          TEXT         COMMENT '通知摘要',
    voice_text       TEXT         COMMENT '老人语音原文',
    status           VARCHAR(10)  DEFAULT 'unread' COMMENT 'unread/read',
    create_time      DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_receiver_open (receiver_open_id),
    INDEX idx_caregiver (caregiver_id),
    INDEX idx_elderly (elderly_user_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='健康风险通知表';

INSERT INTO family_bind (elderly_user_id, role, name, phone, open_id, caregiver_id, notify_enabled)
SELECT id, 'family', '李明', '13900000002', 'demo_family_001', NULL, 1
FROM elderly_user WHERE open_id = 'demo_openid_001'
  AND NOT EXISTS (SELECT 1 FROM family_bind b WHERE b.open_id = 'demo_family_001');

INSERT INTO family_bind (elderly_user_id, role, name, phone, open_id, caregiver_id, notify_enabled)
SELECT e.id, 'caregiver', '王强', '13800000001', NULL, c.id, 1
FROM elderly_user e, caregiver c
WHERE e.open_id = 'demo_openid_001' AND c.name = '王强'
  AND NOT EXISTS (
      SELECT 1 FROM family_bind b WHERE b.elderly_user_id = e.id AND b.caregiver_id = c.id
  );

INSERT INTO family_bind (elderly_user_id, role, name, phone, open_id, caregiver_id, notify_enabled)
SELECT id, 'family', '张丽', '13900000004', 'demo_family_002', NULL, 1
FROM elderly_user WHERE open_id = 'demo_openid_002'
  AND NOT EXISTS (SELECT 1 FROM family_bind b WHERE b.open_id = 'demo_family_002');

INSERT INTO family_bind (elderly_user_id, role, name, phone, open_id, caregiver_id, notify_enabled)
SELECT e.id, 'caregiver', '陈师傅', '13800000005', NULL, c.id, 1
FROM elderly_user e, caregiver c
WHERE e.open_id = 'demo_openid_002' AND c.name = '陈师傅'
  AND NOT EXISTS (
      SELECT 1 FROM family_bind b WHERE b.elderly_user_id = e.id AND b.caregiver_id = c.id
  );

-- 幂等补齐 health_notify 健康风险通知表（运行库若缺失会导致护工端/家属端「系统繁忙」）
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

-- 幂等补齐 family_bind 老人与家属/护工绑定表（运行库若缺失会导致绑定功能完全不可用）
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

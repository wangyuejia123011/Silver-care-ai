-- 微信小程序订阅消息授权与发送记录表
CREATE TABLE IF NOT EXISTS wx_subscribe_record (
    id              BIGINT       PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    open_id         VARCHAR(64)  NOT NULL COMMENT '接收者微信openId',
    bind_id         BIGINT       COMMENT '关联 family_bind.id',
    template_id     VARCHAR(64)  NOT NULL COMMENT '微信小程序订阅消息模板ID',
    auth_type       VARCHAR(20)  DEFAULT 'once' COMMENT '授权类型：once-一次性订阅 permanent-长期订阅',
    remain_count    INT          DEFAULT 0 COMMENT '剩余可发送次数（一次性订阅用）',
    auth_time       DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '最近授权时间',
    send_time       DATETIME     COMMENT '最近发送时间',
    send_status     VARCHAR(20)  COMMENT '发送状态：success / fail',
    fail_reason     VARCHAR(255) COMMENT '发送失败原因',
    create_time     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_open_id_template (open_id, template_id),
    INDEX idx_bind_id (bind_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='微信小程序订阅消息授权与发送记录';

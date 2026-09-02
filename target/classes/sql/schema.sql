-- ================================================
-- 银龄智护 数据库建表脚本
-- 数据库: elderly_ai
-- 字符集: utf8mb4
-- ================================================

CREATE DATABASE IF NOT EXISTS elderly_ai DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE elderly_ai;

-- ----------------------------
-- 1. 老人用户表
-- ----------------------------
CREATE TABLE IF NOT EXISTS elderly_user (
    id              BIGINT       PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    open_id         VARCHAR(64)  UNIQUE COMMENT '微信openid',
    name            VARCHAR(50)  COMMENT '老人姓名',
    phone           VARCHAR(20)  COMMENT '手机号',
    gender          VARCHAR(10)  COMMENT '性别',
    age             INT          COMMENT '年龄',
    address         VARCHAR(255) COMMENT '居住地址',
    emergency_contact VARCHAR(50) COMMENT '紧急联系人姓名',
    emergency_phone VARCHAR(20)  COMMENT '紧急联系人电话',
    avatar          VARCHAR(255) COMMENT '头像URL',
    remark          TEXT         COMMENT '备注（既往病史等）',
    create_time     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='老人用户信息表';

-- ----------------------------
-- 2. 健康记录表
-- ----------------------------
CREATE TABLE IF NOT EXISTS health_record (
    id                  BIGINT   PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    user_id             BIGINT   COMMENT '关联老人用户ID',
    elderly_name        VARCHAR(50) COMMENT '老人姓名',
    age                 INT      COMMENT '年龄',
    systolic_pressure   INT      COMMENT '收缩压（高压）',
    diastolic_pressure  INT      COMMENT '舒张压（低压）',
    heart_rate          INT      COMMENT '心率',
    blood_sugar         DECIMAL(5,1) COMMENT '血糖',
    temperature         DECIMAL(4,1) COMMENT '体温',
    source              VARCHAR(20)  DEFAULT 'manual' COMMENT '来源：voice/manual',
    voice_text          TEXT     COMMENT '语音识别原文',
    risk_level          VARCHAR(10)  DEFAULT 'low' COMMENT '风险等级：low/medium/high',
    ai_advice           TEXT     COMMENT 'AI健康建议',
    is_alert            TINYINT  DEFAULT 0 COMMENT '是否高危：0-正常 1-高危',
    record_time         DATETIME COMMENT '记录时间',
    create_time         DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_user_id (user_id),
    INDEX idx_record_time (record_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='健康记录表';

-- ----------------------------
-- 3. 护工信息表（Agent5 加权调度核心表）
-- ----------------------------
CREATE TABLE IF NOT EXISTS caregiver (
    id                   BIGINT       PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    name                 VARCHAR(50)  COMMENT '护工姓名',
    phone                VARCHAR(20)  COMMENT '手机号',
    gender               VARCHAR(10)  COMMENT '性别',
    age                  INT          COMMENT '年龄',
    skills               VARCHAR(255) COMMENT '技能标签JSON：["助浴","康复","陪诊","急救","保洁"]',
    area                 VARCHAR(50)  COMMENT '负责区域（如：东区/西区/1号楼/3号楼）',
    current_order_count  INT          DEFAULT 0 COMMENT '当前接单数（负载均衡依据）',
    total_order_count    INT          DEFAULT 0 COMMENT '累计接单数（日报排行依据）',
    status               VARCHAR(10)  DEFAULT 'on' COMMENT '状态：on-在岗 off-休息',
    create_time          DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_area (area),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='护工信息表';

-- ----------------------------
-- 4. 护工服务工单表
-- ----------------------------
CREATE TABLE IF NOT EXISTS care_order (
    id                  BIGINT   PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    user_id             BIGINT   COMMENT '关联老人用户ID',
    elderly_name        VARCHAR(50) COMMENT '老人姓名',
    address             VARCHAR(255) COMMENT '老人地址',
    phone               VARCHAR(20)  COMMENT '老人电话',
    order_type          VARCHAR(20)  COMMENT '工单类型：health/daily/emergency',
    demand              TEXT     COMMENT '老人原始需求',
    order_content       TEXT     COMMENT 'AI生成的工单内容',
    need_medical_device TINYINT  DEFAULT 0 COMMENT '是否需要医疗设备：0-否 1-是',
    status              VARCHAR(20)  DEFAULT 'pending' COMMENT '状态：pending/assigned/done/cancelled',
    handler_name        VARCHAR(50)  COMMENT '处理人姓名',
    caregiver_id        BIGINT   COMMENT '指派护工ID（加权调度结果）',
    assigned_time       DATETIME COMMENT '派单时间',
    create_time         DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    finish_time         DATETIME COMMENT '完成时间',
    update_time         DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_user_id (user_id),
    INDEX idx_status (status),
    INDEX idx_caregiver_id (caregiver_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='护工服务工单表';

-- ----------------------------
-- 演示种子数据：5名护工（不同技能/区域/负载，用于展示加权调度算法）
-- ----------------------------
INSERT INTO caregiver (name, phone, gender, age, skills, area, current_order_count, total_order_count, status) VALUES
('王强',   '13800000001', '男', 35, '["急救","康复"]',    '3号楼', 2, 87,  'on'),
('李桂芳', '13800000002', '女', 42, '["助浴","保洁"]',    '3号楼', 0, 120, 'on'),
('张建军', '13800000003', '男', 38, '["康复","陪诊"]',    '东区',  1, 65,  'on'),
('刘小梅', '13800000004', '女', 29, '["陪诊","保洁"]',    '西区',  3, 43,  'on'),
('陈师傅', '13800000005', '男', 45, '["急救","康复","陪诊"]', '东区', 1, 156, 'on');

-- ----------------------------
-- 5. 反诈预警记录表
-- ----------------------------
CREATE TABLE IF NOT EXISTS fraud_alert (
    id              BIGINT   PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    user_id         BIGINT   COMMENT '关联老人用户ID',
    elderly_name    VARCHAR(50) COMMENT '老人姓名',
    content         TEXT     COMMENT '原始对话内容',
    hit_keywords    VARCHAR(255) COMMENT '命中关键词',
    risk_level      VARCHAR(10)  COMMENT '风险等级：low/medium/high',
    detect_type     VARCHAR(10)  COMMENT '检测方式：keyword/ai',
    ai_tip          TEXT     COMMENT 'AI提示信息',
    is_handled      TINYINT  DEFAULT 0 COMMENT '是否已处理：0-未处理 1-已处理',
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_user_id (user_id),
    INDEX idx_is_handled (is_handled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='反诈预警记录表';

-- ----------------------------
-- 6. 对话记录表
-- ----------------------------
CREATE TABLE IF NOT EXISTS chat_log (
    id              BIGINT   PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    user_id         BIGINT   COMMENT '关联老人用户ID',
    chat_type       VARCHAR(20)  COMMENT '对话类型：health/fraud/order/chat',
    user_input      TEXT     COMMENT '用户输入',
    ai_reply        TEXT     COMMENT 'AI回复',
    emotion         VARCHAR(10)  COMMENT '情绪标签',
    source          VARCHAR(10)  DEFAULT 'text' COMMENT '来源：voice/text',
    create_time     DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话记录表';

-- ----------------------------
-- 7. 社区养老日报表
-- ----------------------------
CREATE TABLE IF NOT EXISTS daily_report (
    id                  BIGINT   PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    report_date         DATE     UNIQUE COMMENT '报告日期',
    health_record_count INT      DEFAULT 0 COMMENT '健康记录总数',
    high_risk_count     INT      DEFAULT 0 COMMENT '高危老人数量',
    fraud_block_count   INT      DEFAULT 0 COMMENT '反诈拦截条数',
    order_count         INT      DEFAULT 0 COMMENT '工单数量',
    summary             TEXT     COMMENT 'AI日报摘要',
    create_time         DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='社区养老日报表';

-- ----------------------------
-- 演示种子数据：老人用户（演示场景用）
-- ----------------------------
INSERT INTO elderly_user (open_id, name, phone, gender, age, address, emergency_contact, emergency_phone, remark) VALUES
('demo_openid_001', '李奶奶', '13900000001', '女', 72, '阳光社区3号楼201', '李明', '13900000002', '高血压病史10年'),
('demo_openid_002', '张大爷', '13900000003', '男', 68, '阳光社区东区102', '张丽', '13900000004', '2型糖尿病');

-- ----------------------------
-- 8. 家属/护工绑定表
-- ----------------------------
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

-- ----------------------------
-- 9. 健康风险通知表
-- ----------------------------
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
SELECT id, 'family', '李明', '13900000002', 'demo_family_001', NULL, 1 FROM elderly_user WHERE open_id = 'demo_openid_001';
INSERT INTO family_bind (elderly_user_id, role, name, phone, open_id, caregiver_id, notify_enabled)
SELECT e.id, 'caregiver', '王强', '13800000001', NULL, c.id, 1
FROM elderly_user e, caregiver c WHERE e.open_id = 'demo_openid_001' AND c.name = '王强';
INSERT INTO family_bind (elderly_user_id, role, name, phone, open_id, caregiver_id, notify_enabled)
SELECT id, 'family', '张丽', '13900000004', 'demo_family_002', NULL, 1 FROM elderly_user WHERE open_id = 'demo_openid_002';
INSERT INTO family_bind (elderly_user_id, role, name, phone, open_id, caregiver_id, notify_enabled)
SELECT e.id, 'caregiver', '陈师傅', '13800000005', NULL, c.id, 1
FROM elderly_user e, caregiver c WHERE e.open_id = 'demo_openid_002' AND c.name = '陈师傅';

-- ----------------------------
-- 10. 服药提醒计划表
-- ----------------------------
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

-- 演示种子数据
INSERT INTO medication_reminder (user_id, drug_name, dose_time, dosage, enabled)
SELECT id, '降压药',  '08:00:00', '1片', 1 FROM elderly_user WHERE open_id = 'demo_openid_001';
INSERT INTO medication_reminder (user_id, drug_name, dose_time, dosage, enabled)
SELECT id, '降糖药',  '12:30:00', '1粒', 1 FROM elderly_user WHERE open_id = 'demo_openid_002';
INSERT INTO medication_reminder (user_id, drug_name, dose_time, dosage, enabled)
SELECT id, '安神药',  '21:00:00', '1片', 1 FROM elderly_user WHERE open_id = 'demo_openid_002';

-- ----------------------------
-- 11. 微信小程序订阅消息授权与发送记录表
-- ----------------------------
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

-- ================================================
-- 银龄智护 2.0 → 2.1 升级脚本（在已有数据库上执行）
-- 数据库: elderly_ai（虚拟机 192.168.38.134）
-- 用法: mysql -uroot -p elderly_ai < upgrade-v2.sql
-- ================================================
USE elderly_ai;

-- ----------------------------
-- 1. 新增护工表（Agent5 加权调度核心表）
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
-- 2. 工单表新增指派字段 【删掉 IF NOT EXISTS】
-- ----------------------------
ALTER TABLE care_order
    ADD COLUMN caregiver_id BIGINT COMMENT '指派护工ID（加权调度结果）' AFTER handler_name,
    ADD COLUMN assigned_time DATETIME COMMENT '派单时间' AFTER caregiver_id,
    ADD INDEX idx_caregiver_id (caregiver_id);

-- ----------------------------
-- 3. 演示种子数据：5名护工（不同技能/区域/负载，演示加权调度算法）
-- ----------------------------
INSERT INTO caregiver (name, phone, gender, age, skills, area, current_order_count, total_order_count, status) VALUES
('王强',   '13800000001', '男', 35, '["急救","康复"]',        '3号楼', 2, 87,  'on'),
('李桂芳', '13800000002', '女', 42, '["助浴","保洁"]',        '3号楼', 0, 120, 'on'),
('张建军', '13800000003', '男', 38, '["康复","陪诊"]',        '东区',  1, 65,  'on'),
('刘小梅', '13800000004', '女', 29, '["陪诊","保洁"]',        '西区',  3, 43,  'on'),
('陈师傅', '13800000005', '男', 45, '["急救","康复","陪诊"]', '东区',  1, 156, 'on');

-- ----------------------------
-- 4. 演示种子数据：老人用户（如已存在会因open_id唯一约束跳过报错，可忽略）
-- ----------------------------
INSERT IGNORE INTO elderly_user (open_id, name, phone, gender, age, address, emergency_contact, emergency_phone, remark) VALUES
('demo_openid_001', '李奶奶', '13900000001', '女', 72, '阳光社区3号楼201', '李明', '13900000002', '高血压病史10年'),
('demo_openid_002', '张大爷', '13900000003', '男', 68, '阳光社区东区102', '张丽', '13900000004', '2型糖尿病');


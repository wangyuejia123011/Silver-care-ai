-- ============================================================
-- 护工详情（个人主页）用到的扩展字段
-- 新增列：avatar / bio / experience_years / rating / rating_count /
--        service_time / can_carry_device
-- 用途：GET /api/caregiver/{id}/profile 护工详情接口、PUT /api/caregiver/{id}
-- 说明：本脚本幂等（列已存在则跳过），可重复执行。
-- 执行顺序很重要：先执行本脚本加列，再 push + 重新部署后端，
-- 否则后端一启动用新列查询会报 Unknown column。
--
-- ⚠️ 本版本刻意不使用 DELIMITER / CREATE PROCEDURE。
--    原因：DELIMITER 是 mysql 命令行客户端的伪指令，微信云托管 DMC
--    （网页数据库管理页）不识别，整段执行会报
--    Error 1064 ... near 'DELIMITER $$ CREATE PROCEDURE'。
--    改用「PREPARE + EXECUTE」动态 SQL，每条都是单语句，
--    DMC、Navicat、mysql 命令行三处都能直接跑。
--
-- 说明：ADD COLUMN 后不带 AFTER，列顺序无所谓，避免依赖前一列已成功。
-- 若 DMC 报「只能执行单条语句」，改用同目录的
-- upgrade-caregiver-profile-simple.sql（朴素 ALTER 版，仅可执行一次）。
-- ============================================================

-- ---------- 1. 头像图片地址 ----------
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE caregiver ADD COLUMN avatar VARCHAR(500) COMMENT ''头像图片地址''',
    'SELECT ''caregiver.avatar already exists''')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'caregiver' AND COLUMN_NAME = 'avatar');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 2. 个人简介 / 详细介绍 ----------
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE caregiver ADD COLUMN bio VARCHAR(1000) COMMENT ''个人简介/详细介绍''',
    'SELECT ''caregiver.bio already exists''')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'caregiver' AND COLUMN_NAME = 'bio');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 3. 从业年限 ----------
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE caregiver ADD COLUMN experience_years INT COMMENT ''从业年限''',
    'SELECT ''caregiver.experience_years already exists''')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'caregiver' AND COLUMN_NAME = 'experience_years');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 4. 服务评分 ----------
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE caregiver ADD COLUMN rating DECIMAL(3,1) DEFAULT 5.0 COMMENT ''服务评分''',
    'SELECT ''caregiver.rating already exists''')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'caregiver' AND COLUMN_NAME = 'rating');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 5. 评价人数 ----------
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE caregiver ADD COLUMN rating_count INT DEFAULT 0 COMMENT ''评价人数''',
    'SELECT ''caregiver.rating_count already exists''')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'caregiver' AND COLUMN_NAME = 'rating_count');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 6. 可服务时间 ----------
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE caregiver ADD COLUMN service_time VARCHAR(100) COMMENT ''可服务时间''',
    'SELECT ''caregiver.service_time already exists''')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'caregiver' AND COLUMN_NAME = 'service_time');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 7. 是否愿意携带医疗设备上门（调度匹配用） ----------
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE caregiver ADD COLUMN can_carry_device TINYINT DEFAULT 0 COMMENT ''是否愿携带医疗设备上门：0-否 1-是''',
    'SELECT ''caregiver.can_carry_device already exists''')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'caregiver' AND COLUMN_NAME = 'can_carry_device');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 8. 初始化默认值 ----------
-- 老护工没有评价数据时给个默认评分，避免详情页显示 0 分
UPDATE caregiver SET rating = 5.0, rating_count = 0 WHERE rating IS NULL;

-- 具备急救/康复/陪诊技能的视为可带设备；助浴/保洁为主的不可带
UPDATE caregiver SET can_carry_device = 1
    WHERE can_carry_device = 0
      AND (skills LIKE '%急救%' OR skills LIKE '%康复%' OR skills LIKE '%陪诊%');
UPDATE caregiver SET can_carry_device = 0
    WHERE skills LIKE '%助浴%' AND skills NOT LIKE '%急救%' AND skills NOT LIKE '%康复%';

-- ---------- 9. 校验：执行完取消下面注释跑一下，确认 7 列都在 ----------
-- SELECT COLUMN_NAME FROM information_schema.COLUMNS
--   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'caregiver'
--   ORDER BY ORDINAL_POSITION;

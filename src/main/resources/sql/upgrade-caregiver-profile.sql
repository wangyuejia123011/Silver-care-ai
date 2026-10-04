-- ============================================================
-- 护工详情（个人主页）用到的扩展字段
-- 新增列：avatar / bio / experience_years / rating / rating_count /
--        service_time / can_carry_device
-- 用途：GET /api/caregiver/{id}/profile 护工详情接口
-- 说明：本脚本幂等（已存在则跳过）。
-- 执行顺序很重要：先执行本脚本加列，再 push + 重新部署后端，
-- 否则后端一启动用新列查询会报 Unknown column。
-- 执行方式（任选其一）：
--   1) 云托管控制台 → 数据库管理(DMC) → 选 elderly_ai → 粘贴执行；
--   2) 后端容器 Webshell 里用 mysql 命令行执行；
--   3) 本地 Navicat 连库后执行。
-- ============================================================

DROP PROCEDURE IF EXISTS _add_caregiver_profile_cols;
DELIMITER $$
CREATE PROCEDURE _add_caregiver_profile_cols()
BEGIN
    -- 头像图片地址
    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'caregiver' AND COLUMN_NAME = 'avatar'
    ) THEN
        ALTER TABLE caregiver ADD COLUMN avatar VARCHAR(500) COMMENT '头像图片地址' AFTER status;
    END IF;

    -- 个人简介 / 详细介绍
    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'caregiver' AND COLUMN_NAME = 'bio'
    ) THEN
        ALTER TABLE caregiver ADD COLUMN bio VARCHAR(1000) COMMENT '个人简介/详细介绍' AFTER avatar;
    END IF;

    -- 从业年限
    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'caregiver' AND COLUMN_NAME = 'experience_years'
    ) THEN
        ALTER TABLE caregiver ADD COLUMN experience_years INT COMMENT '从业年限' AFTER bio;
    END IF;

    -- 服务评分
    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'caregiver' AND COLUMN_NAME = 'rating'
    ) THEN
        ALTER TABLE caregiver ADD COLUMN rating DECIMAL(3,1) DEFAULT 5.0 COMMENT '服务评分' AFTER experience_years;
    END IF;

    -- 评价人数
    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'caregiver' AND COLUMN_NAME = 'rating_count'
    ) THEN
        ALTER TABLE caregiver ADD COLUMN rating_count INT DEFAULT 0 COMMENT '评价人数' AFTER rating;
    END IF;

    -- 可服务时间
    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'caregiver' AND COLUMN_NAME = 'service_time'
    ) THEN
        ALTER TABLE caregiver ADD COLUMN service_time VARCHAR(100) COMMENT '可服务时间' AFTER rating_count;
    END IF;

    -- 是否愿意携带医疗设备上门（调度匹配用）
    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'caregiver' AND COLUMN_NAME = 'can_carry_device'
    ) THEN
        ALTER TABLE caregiver ADD COLUMN can_carry_device TINYINT DEFAULT 0 COMMENT '是否愿携带医疗设备上门：0-否 1-是' AFTER service_time;
    END IF;
END $$
DELIMITER ;

CALL _add_caregiver_profile_cols();
DROP PROCEDURE IF EXISTS _add_caregiver_profile_cols;

-- 老护工没有评价数据时，给个默认评分，避免详情页显示 0 分
UPDATE caregiver SET rating = 5.0, rating_count = 0 WHERE rating IS NULL;

-- 校验用（执行完看一眼是否都有列）
-- SELECT id, name, avatar, bio, experience_years, rating, rating_count, service_time, can_carry_device FROM caregiver;

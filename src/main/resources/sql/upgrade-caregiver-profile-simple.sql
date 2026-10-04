-- ============================================================
-- 【备用脚本】护工详情扩展列 —— 朴素版（不带任何条件判断）
-- 用途：DMC 对多语句 / 预处理语句支持不完美时的兜底方案。
--       主脚本 upgrade-caregiver-profile.sql 用 PREPARE 动态 SQL，
--       若你的 DMC 报语法错误，就改跑这个。
-- 注意：本脚本【只能执行一次】。若提示 Duplicate column name，
--       说明列已存在，属正常现象，不是错误。
-- 建议：先跑主脚本；主脚本报错再跑这个。
-- ============================================================

ALTER TABLE caregiver ADD COLUMN avatar           VARCHAR(500) COMMENT '头像图片地址';
ALTER TABLE caregiver ADD COLUMN bio              VARCHAR(1000) COMMENT '个人简介/详细介绍';
ALTER TABLE caregiver ADD COLUMN experience_years INT          COMMENT '从业年限';
ALTER TABLE caregiver ADD COLUMN rating           DECIMAL(3,1) DEFAULT 5.0 COMMENT '服务评分';
ALTER TABLE caregiver ADD COLUMN rating_count     INT          DEFAULT 0 COMMENT '评价人数';
ALTER TABLE caregiver ADD COLUMN service_time     VARCHAR(100) COMMENT '可服务时间';
ALTER TABLE caregiver ADD COLUMN can_carry_device TINYINT      DEFAULT 0 COMMENT '是否愿携带医疗设备上门：0-否 1-是';

-- 初始化默认值
UPDATE caregiver SET rating = 5.0, rating_count = 0 WHERE rating IS NULL;
UPDATE caregiver SET can_carry_device = 1
    WHERE can_carry_device = 0
      AND (skills LIKE '%急救%' OR skills LIKE '%康复%' OR skills LIKE '%陪诊%');
UPDATE caregiver SET can_carry_device = 0
    WHERE skills LIKE '%助浴%' AND skills NOT LIKE '%急救%' AND skills NOT LIKE '%康复%';

-- 校验：执行完取消下面注释跑一下，确认 7 列都在
-- SELECT COLUMN_NAME FROM information_schema.COLUMNS
--   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'caregiver'
--   ORDER BY ORDINAL_POSITION;

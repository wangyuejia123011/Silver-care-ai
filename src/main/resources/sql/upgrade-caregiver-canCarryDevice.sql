-- ============================================================
-- 为 caregiver 表补充 can_carry_device 字段（能否携带医疗设备上门）
-- 原因：呼叫护工场景化加权调度需要区分"可携带医疗设备的护工"与"仅日常照料护工"，
--       否则健康类工单无法优先匹配到能带血压计/体温计上门的护工。
-- 说明：本脚本幂等（已存在则跳过），可重复执行。
-- 重要：必须连到【云托管后端服务实际连接的那个库】才能生效！
--   后端在云托管上通过环境变量 DB_URL / DB_USERNAME / DB_PASSWORD 连库，
--   地址在「云托管控制台 → 你的后端服务 → 环境变量」里查看，不要照抄本地 192.168.38.134。
--
-- ⚠️ 本版本刻意不使用 DELIMITER / CREATE PROCEDURE。
--    DELIMITER 是 mysql 命令行客户端的伪指令，微信云托管 DMC（网页数据库管理页）
--    不识别，整段执行会报 Error 1064。
--    改用「PREPARE + EXECUTE」动态 SQL，DMC / Navicat / mysql 命令行三处通用。
--
-- 注意：护工详情要的另外 6 个列（avatar / bio / experience_years /
--     rating / rating_count / service_time）在 upgrade-caregiver-profile.sql 里。
-- ============================================================

SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE caregiver ADD COLUMN can_carry_device TINYINT DEFAULT 0 COMMENT ''能否携带医疗设备上门：0-否 1-是''',
    'SELECT ''caregiver.can_carry_device already exists''')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'caregiver' AND COLUMN_NAME = 'can_carry_device');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 给演示种子护工补上"能否携带医疗设备"标记：
-- 具备急救/康复/陪诊技能的护工视为可带设备；助浴/保洁为主的护工视为不可带设备。
UPDATE caregiver SET can_carry_device = 1
    WHERE can_carry_device = 0
      AND (skills LIKE '%急救%' OR skills LIKE '%康复%' OR skills LIKE '%陪诊%');
UPDATE caregiver SET can_carry_device = 0
    WHERE skills LIKE '%助浴%' AND skills NOT LIKE '%急救%' AND skills NOT LIKE '%康复%';

-- 校验：取消下面注释，确认列存在
-- SELECT COLUMN_NAME FROM information_schema.COLUMNS
--   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'caregiver'
--     AND COLUMN_NAME = 'can_carry_device';

-- ============================================================
-- 为 caregiver 表补充 can_carry_device 字段（能否携带医疗设备上门）
-- 原因：呼叫护工场景化加权调度需要区分"可携带医疗设备的护工"与"仅日常照料护工"，
--       否则健康类工单无法优先匹配到能带血压计/体温计上门的护工。
-- 说明：本脚本幂等（已存在则跳过），直接执行即可，无需重启后端。
-- 重要：必须连到【云托管后端服务实际连接的那个库】才能生效！
--   后端在云托管上通过环境变量 DB_URL / DB_USERNAME / DB_PASSWORD 连库，
--   地址在「云托管控制台 → 你的后端服务 → 环境变量」里查看，不要照抄本地 192.168.38.134。
-- 三种执行方式（任选其一）：
--   ① 云托管控制台数据库管理页(DMC)：选 elderly_ai 库 → 新建查询 → 粘贴本脚本全文 → 执行
--   ② 登录后端容器 Webshell：mysql -h$DB_URL主机 -u$DB_USERNAME -p$DB_PASSWORD elderly_ai < 本脚本
--   ③ 本地 Navicat/Workbench：填云托管库的公网地址连接 elderly_ai 后执行本脚本
-- 若提示 "Duplicate column name" 可忽略（说明列已存在）。
-- ============================================================

DROP PROCEDURE IF EXISTS _add_caregiver_device_col;
DELIMITER $$
CREATE PROCEDURE _add_caregiver_device_col()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'caregiver' AND COLUMN_NAME = 'can_carry_device'
    ) THEN
        ALTER TABLE caregiver ADD COLUMN can_carry_device TINYINT DEFAULT 0
            COMMENT '能否携带医疗设备上门：0-否 1-是';
    END IF;
END$$
DELIMITER ;
CALL _add_caregiver_device_col();
DROP PROCEDURE IF EXISTS _add_caregiver_device_col;

-- 给演示种子护工补上"能否携带医疗设备"标记：
-- 具备急救/康复技能的护工视为可带设备；助浴/保洁为主的护工视为不可带设备。
UPDATE caregiver SET can_carry_device = 1
    WHERE can_carry_device = 0
      AND (skills LIKE '%急救%' OR skills LIKE '%康复%' OR skills LIKE '%陪诊%');
UPDATE caregiver SET can_carry_device = 0
    WHERE skills LIKE '%助浴%' AND skills NOT LIKE '%急救%' AND skills NOT LIKE '%康复%';

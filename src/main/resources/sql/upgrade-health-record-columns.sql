-- ============================================================
-- 补齐 health_record 表缺失的 elderly_name / age 字段
-- 原因：部分旧库建表时未包含这两列（代码后加的冗余字段），
--       导致查询/保存健康记录时报 "Unknown column 'elderly_name'"，
--       表现为前端“系统繁忙，请稍后重试”、健康记录保存不上、历史记录空白。
-- 说明：本脚本幂等（已存在则跳过），直接执行即可，无需重启后端。
-- 用法（Linux 虚拟机 SSH 中执行）：
--   mysql -u root -p elderly_ai < upgrade-health-record-columns.sql
-- 若使用 Navicat / Workbench 等图形工具，可不执行本文件，直接单独运行下面两句
-- （若提示 “Duplicate column name” 属正常，说明列已存在）：
--   ALTER TABLE health_record ADD COLUMN elderly_name VARCHAR(50) COMMENT '老人姓名（冗余）';
--   ALTER TABLE health_record ADD COLUMN age INT COMMENT '年龄（冗余）';
-- ============================================================

DROP PROCEDURE IF EXISTS _add_hr_cols;
DELIMITER $$
CREATE PROCEDURE _add_hr_cols()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME  = 'health_record'
          AND COLUMN_NAME = 'elderly_name'
    ) THEN
        ALTER TABLE health_record
            ADD COLUMN elderly_name VARCHAR(50) COMMENT '老人姓名（冗余，方便查询）';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME  = 'health_record'
          AND COLUMN_NAME = 'age'
    ) THEN
        ALTER TABLE health_record
            ADD COLUMN age INT COMMENT '年龄（冗余，方便查询）';
    END IF;
END$$
DELIMITER ;

CALL _add_hr_cols();
DROP PROCEDURE IF EXISTS _add_hr_cols;

-- 校验：应输出 elderly_name 与 age 两行
SELECT COLUMN_NAME, DATA_TYPE
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'health_record'
  AND COLUMN_NAME IN ('elderly_name', 'age');

-- ============================================================
-- 一次性补齐 health_record 表当前代码所需的全部列
-- 原因：部分旧库/测试库建表语句不完整，只含少量列，
--       导致所有 SELECT/INSERT 报 "Unknown column 'xxx' in 'field list'"，
--       表现为：
--         1) 首页"今日关怀"一直兜底、不调用 AI；
--         2) 健康记录保存失败；
--         3) 历史记录加载失败/空白。
-- 说明：本脚本幂等（已存在则跳过），直接执行即可，无需重启后端。
-- 用法（Linux 虚拟机 SSH 中执行）：
--   mysql -h 192.168.38.134 -P 3306 -u root -p elderly_ai < upgrade-health-record-full-columns.sql
-- 若提示 "Duplicate column name" 可忽略（说明列已存在）。
-- 执行完成后请再执行：
--   curl -s "http://localhost:8080/api/health/list/1"
-- 若返回 {"code":200,"data":[]} 且无报错，说明数据库列已补齐。
-- ============================================================

DROP PROCEDURE IF EXISTS _add_hr_all_cols;
DELIMITER $$
CREATE PROCEDURE _add_hr_all_cols()
BEGIN
    -- 老人姓名（冗余）
    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'health_record' AND COLUMN_NAME = 'elderly_name'
    ) THEN
        ALTER TABLE health_record ADD COLUMN elderly_name VARCHAR(50) COMMENT '老人姓名（冗余，方便查询）';
    END IF;

    -- 年龄（冗余）
    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'health_record' AND COLUMN_NAME = 'age'
    ) THEN
        ALTER TABLE health_record ADD COLUMN age INT COMMENT '年龄（冗余，方便查询）';
    END IF;

    -- 收缩压（高压）
    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'health_record' AND COLUMN_NAME = 'systolic_pressure'
    ) THEN
        ALTER TABLE health_record ADD COLUMN systolic_pressure INT COMMENT '收缩压（高压）';
    END IF;

    -- 舒张压（低压）
    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'health_record' AND COLUMN_NAME = 'diastolic_pressure'
    ) THEN
        ALTER TABLE health_record ADD COLUMN diastolic_pressure INT COMMENT '舒张压（低压）';
    END IF;

    -- 心率
    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'health_record' AND COLUMN_NAME = 'heart_rate'
    ) THEN
        ALTER TABLE health_record ADD COLUMN heart_rate INT COMMENT '心率';
    END IF;

    -- 血糖
    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'health_record' AND COLUMN_NAME = 'blood_sugar'
    ) THEN
        ALTER TABLE health_record ADD COLUMN blood_sugar DECIMAL(5,1) COMMENT '血糖';
    END IF;

    -- 体温
    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'health_record' AND COLUMN_NAME = 'temperature'
    ) THEN
        ALTER TABLE health_record ADD COLUMN temperature DECIMAL(4,1) COMMENT '体温';
    END IF;

    -- 来源：voice/manual
    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'health_record' AND COLUMN_NAME = 'source'
    ) THEN
        ALTER TABLE health_record ADD COLUMN source VARCHAR(20) DEFAULT 'manual' COMMENT '来源：voice/manual';
    END IF;

    -- 语音识别原文
    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'health_record' AND COLUMN_NAME = 'voice_text'
    ) THEN
        ALTER TABLE health_record ADD COLUMN voice_text TEXT COMMENT '语音识别原文';
    END IF;

    -- 风险等级
    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'health_record' AND COLUMN_NAME = 'risk_level'
    ) THEN
        ALTER TABLE health_record ADD COLUMN risk_level VARCHAR(10) DEFAULT 'low' COMMENT '风险等级：low/medium/high';
    END IF;

    -- AI 健康建议
    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'health_record' AND COLUMN_NAME = 'ai_advice'
    ) THEN
        ALTER TABLE health_record ADD COLUMN ai_advice TEXT COMMENT 'AI健康建议';
    END IF;

    -- 是否高危
    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'health_record' AND COLUMN_NAME = 'is_alert'
    ) THEN
        ALTER TABLE health_record ADD COLUMN is_alert TINYINT DEFAULT 0 COMMENT '是否高危：0-正常 1-高危';
    END IF;

    -- 记录时间
    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'health_record' AND COLUMN_NAME = 'record_time'
    ) THEN
        ALTER TABLE health_record ADD COLUMN record_time DATETIME COMMENT '记录时间';
    END IF;

    -- 创建时间
    IF NOT EXISTS (
        SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'health_record' AND COLUMN_NAME = 'create_time'
    ) THEN
        ALTER TABLE health_record ADD COLUMN create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';
    END IF;
END$$
DELIMITER ;

CALL _add_hr_all_cols();
DROP PROCEDURE IF EXISTS _add_hr_all_cols;

-- 校验：应输出 id, user_id 以及上面补全的全部列
SELECT COLUMN_NAME, DATA_TYPE, COLUMN_DEFAULT, COLUMN_COMMENT
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'health_record'
ORDER BY ORDINAL_POSITION;

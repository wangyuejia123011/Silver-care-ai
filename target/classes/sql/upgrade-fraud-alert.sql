-- 反诈预警记录表列补齐脚本
-- 适用场景：运行库中的 fraud_alert 表缺少 matched_cases 等列，导致 /api/fraud/list/{userId} 查询报错 Unknown column
-- 执行方式：在 Ubuntu 虚拟机连后端真正使用的 MySQL（192.168.38.134:3306/elderly_ai）执行
-- mysql -h 192.168.38.134 -P 3306 -u root -p elderly_ai < upgrade-fraud-alert.sql
-- 密码：Root@123456
-- 本脚本幂等：已存在的列会自动跳过

USE elderly_ai;

SET @db_name = 'elderly_ai';
SET @tbl_name = 'fraud_alert';

-- 补齐 matched_cases 列（RAG 命中的相似案例片段）
SET @has_matched_cases = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                          WHERE TABLE_SCHEMA = @db_name AND TABLE_NAME = @tbl_name AND COLUMN_NAME = 'matched_cases');
SET @sql_matched_cases = IF(@has_matched_cases = 0,
                            'ALTER TABLE fraud_alert ADD COLUMN matched_cases TEXT COMMENT "RAG 命中的相似反诈案例片段，多条用换行分隔"',
                            'SELECT "matched_cases 列已存在，跳过" AS msg');
PREPARE stmt FROM @sql_matched_cases; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 校验最终列
SELECT COLUMN_NAME, DATA_TYPE, COLUMN_COMMENT
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = @db_name AND TABLE_NAME = @tbl_name
ORDER BY ORDINAL_POSITION;

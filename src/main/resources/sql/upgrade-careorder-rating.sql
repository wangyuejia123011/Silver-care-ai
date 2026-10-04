-- ============================================================
-- 为 care_order 表补充评价字段（工单完成后老人给护工打分）
-- 新增列：rating（1-5 星，NULL=未评价）/ rating_comment（评价内容）/ rating_time（评价时间）
--
-- 评分汇总口径：每个有效评价等权（即简单平均），不做时效衰减。
-- 每次新增评价后，后端会重新汇总该护工【所有工单】评分并回写
--   caregiver.rating（保留一位小数） 与 caregiver.rating_count（评价人数），
-- 护工详情页 GET /api/caregiver/{id}/profile 即时展示最新加权平均分。
--
-- ⚠️ 本脚本刻意不使用 DELIMITER / CREATE PROCEDURE。
--    DELIMITER 是 mysql 命令行客户端的伪指令，微信云托管 DMC 不识别，
--    整段执行会报 Error 1064。改用 PREPARE + EXECUTE 动态 SQL。
--
-- 执行方式：
--   ① 云托管控制台 → 数据库管理(DMC) → 选 elderly_ai → 粘贴全文 → 执行
--   ② 后端容器 Webshell：mysql -h$DB_URL主机 -u$DB_USERNAME -p$DB_PASSWORD elderly_ai < 本脚本
-- 说明：本脚本幂等（列已存在则跳过），可重复执行。
-- ============================================================

-- ---------- 1. 评分（1-5 星，NULL 表示未评价） ----------
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE care_order ADD COLUMN rating TINYINT COMMENT ''老人评分：1-5星，NULL=未评价''',
    'SELECT ''care_order.rating already exists''')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'care_order' AND COLUMN_NAME = 'rating');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 2. 评价内容 ----------
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE care_order ADD COLUMN rating_comment VARCHAR(200) COMMENT ''评价内容''',
    'SELECT ''care_order.rating_comment already exists''')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'care_order' AND COLUMN_NAME = 'rating_comment');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 3. 评价时间 ----------
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE care_order ADD COLUMN rating_time DATETIME COMMENT ''评价时间''',
    'SELECT ''care_order.rating_time already exists''')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'care_order' AND COLUMN_NAME = 'rating_time');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 4. 回填护工档案的评分基线 ----------
-- 老工单没有评分，这里给护工一个合理的初始展示值（5.0 分 / 0 人评价）。
-- 注意：rating_count 记 0 而不是 1，因为「0 人评价」才符合"还没有人评价过"的事实；
-- 详情页会显示"暂无评分"字样，评价过一张单后就会变成真实均分。
UPDATE caregiver SET rating = 5.0 WHERE rating IS NULL;
UPDATE caregiver SET rating_count = 0 WHERE rating_count IS NULL;

-- ---------- 5. 校验：执行完取消下面注释，确认 3 列都在 ----------
-- SELECT COLUMN_NAME, COLUMN_TYPE, COLUMN_COMMENT FROM information_schema.COLUMNS
--   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'care_order'
--     AND COLUMN_NAME IN ('rating', 'rating_comment', 'rating_time');

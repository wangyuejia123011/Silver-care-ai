-- ============================================================
-- 银龄智护 v2.1 数据库索引优化脚本
-- 针对高频查询字段添加索引，提升查询性能
-- 执行前请备份数据库！
-- ============================================================

-- 1. health_record 健康记录表
-- 高频查询：按用户+时间范围查询今日/近期记录、统计高危数
ALTER TABLE health_record ADD INDEX idx_user_record_time (user_id, record_time);
ALTER TABLE health_record ADD INDEX idx_user_risk (user_id, risk_level);
ALTER TABLE health_record ADD INDEX idx_is_alert (is_alert);

-- 2. care_order 护工工单表
-- 高频查询：按用户/护工/状态查询工单
ALTER TABLE care_order ADD INDEX idx_user_id (user_id);
ALTER TABLE care_order ADD INDEX idx_caregiver_id (caregiver_id);
ALTER TABLE care_order ADD INDEX idx_status (status);
ALTER TABLE care_order ADD INDEX idx_create_time (create_time);

-- 3. fraud_alert 反诈预警表
-- 高频查询：按用户查询反诈记录
ALTER TABLE fraud_alert ADD INDEX idx_user_id (user_id);
ALTER TABLE fraud_alert ADD INDEX idx_is_handled (is_handled);
ALTER TABLE fraud_alert ADD INDEX idx_create_time (create_time);

-- 4. health_notify 健康通知表
-- 高频查询：按接收人(openId/caregiverId)查询收件箱、统计未读数
ALTER TABLE health_notify ADD INDEX idx_receiver_open_id (receiver_open_id);
ALTER TABLE health_notify ADD INDEX idx_caregiver_id (caregiver_id);
ALTER TABLE health_notify ADD INDEX idx_status (status);
ALTER TABLE health_notify ADD INDEX idx_elderly_user_id (elderly_user_id);

-- 5. chat_log 对话记录表
-- 高频查询：按用户查询对话历史
ALTER TABLE chat_log ADD INDEX idx_user_create_time (user_id, create_time);
ALTER TABLE chat_log ADD INDEX idx_chat_type (chat_type);

-- 6. family_bind 家属绑定表
-- 高频查询：按老人查询绑定关系、按openId查询家属登录
ALTER TABLE family_bind ADD INDEX idx_elderly_user_id (elderly_user_id);
ALTER TABLE family_bind ADD INDEX idx_open_id (open_id);
ALTER TABLE family_bind ADD INDEX idx_role (role);

-- 7. elderly_user 老人用户表
-- 高频查询：按openId登录
ALTER TABLE elderly_user ADD UNIQUE INDEX idx_open_id (open_id);

-- 8. caregiver 护工表
-- 高频查询：按状态查询在岗护工、按技能匹配
ALTER TABLE caregiver ADD INDEX idx_status (status);
ALTER TABLE caregiver ADD INDEX idx_area (area);

-- 9. daily_report 日报表
-- 高频查询：按日期查询日报
ALTER TABLE daily_report ADD UNIQUE INDEX idx_report_date (report_date);

-- ============================================================
-- 索引优化说明：
-- 1. 联合索引 idx_user_record_time 覆盖"按用户+时间范围"查询，
--    用于 health_record 的今日记录、近期记录、时间段统计等高频查询。
-- 2. health_notify 的 receiver_open_id 和 caregiver_id 是通知中心
--    收件箱查询的核心字段，单独建索引可大幅提升家属/护工端加载速度。
-- 3. elderly_user 的 open_id 设为唯一索引，保证微信登录的唯一性。
-- 4. daily_report 的 report_date 设为唯一索引，避免重复生成同日日报。
-- 5. 所有 create_time 索引用于按时间排序和范围查询。
--
-- 执行后可通过以下SQL验证索引是否生效：
--   EXPLAIN SELECT * FROM health_record WHERE user_id = 1 AND record_time >= '2024-01-01';
--   EXPLAIN SELECT * FROM health_notify WHERE receiver_open_id = 'xxx' AND status = 'unread';
-- ============================================================

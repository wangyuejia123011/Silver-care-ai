-- ===================================================
-- v2.1 增量升级：反诈 RAG 命中案例持久化（fraud_alert.matched_cases）
-- 数据库: elderly_ai
-- ===================================================
-- 仅当表已存在时执行升级，全新环境请忽略（schema.sql 已含 matched_cases）。

ALTER TABLE fraud_alert
    ADD COLUMN matched_cases TEXT NULL COMMENT 'RAG 命中案例片段，多条用 || 分隔' AFTER ai_tip;

-- 注意：升级前请先在 application.properties 启用 chroma.fraud.collection 配置项

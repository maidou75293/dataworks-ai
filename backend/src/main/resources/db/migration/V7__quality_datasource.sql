-- 质量规则支持选择数据源：PG（平台库，默认）或 DORIS（分析库）。
ALTER TABLE platform.quality_rule
    ADD COLUMN IF NOT EXISTS datasource varchar(16) NOT NULL DEFAULT 'PG';

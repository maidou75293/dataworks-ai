-- 连接器配置必须保留字段顺序：schema.fields 的顺序决定列顺序，
-- Prometheus 等连接器按位置构造 Row，jsonb 会重排键导致类型错位。
-- 改用 json 类型（文本保序）。
ALTER TABLE platform.sync_task ALTER COLUMN source_config DROP DEFAULT;
ALTER TABLE platform.sync_task ALTER COLUMN source_config TYPE json USING source_config::text::json;
ALTER TABLE platform.sync_task ALTER COLUMN source_config SET DEFAULT '{}'::json;

ALTER TABLE platform.sync_task ALTER COLUMN sink_config DROP DEFAULT;
ALTER TABLE platform.sync_task ALTER COLUMN sink_config TYPE json USING sink_config::text::json;
ALTER TABLE platform.sync_task ALTER COLUMN sink_config SET DEFAULT '{}'::json;

ALTER TABLE platform.datasource ALTER COLUMN config_json DROP DEFAULT;
ALTER TABLE platform.datasource ALTER COLUMN config_json TYPE json USING config_json::text::json;
ALTER TABLE platform.datasource ALTER COLUMN config_json SET DEFAULT '{}'::json;

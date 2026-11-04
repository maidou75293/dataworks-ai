-- 质量规则改为引用「数据源管理」里的数据源；datasource 列保留给未绑定数据源的老规则。
ALTER TABLE platform.quality_rule
    ADD COLUMN IF NOT EXISTS datasource_id bigint
        REFERENCES platform.datasource(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_quality_rule_datasource ON platform.quality_rule(datasource_id);

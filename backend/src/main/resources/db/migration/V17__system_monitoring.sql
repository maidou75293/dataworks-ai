-- 系统监控告警接入告警中心
ALTER TABLE platform.alert_event
    ADD COLUMN IF NOT EXISTS source varchar(16) NOT NULL DEFAULT 'PLATFORM';

-- 系统告警没有 alert_rule（rule_id 为空），按 event_key 去重。
-- 普通业务告警仍走 (rule_id, event_key) 唯一索引。
CREATE UNIQUE INDEX IF NOT EXISTS uk_alert_event_system_key
    ON platform.alert_event (event_key)
    WHERE rule_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_alert_event_type_created
    ON platform.alert_event (rule_type, created_at DESC);

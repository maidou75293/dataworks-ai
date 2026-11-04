-- V17 的系统告警去重索引谓词用了 rule_id IS NULL，但普通告警在所属规则被删除时
-- 也会被外键置为 NULL，与已存在的同 event_key 事件冲突，导致删除规则 409。
-- 系统事件的稳定标识是 source='SYSTEM'，索引与 ON CONFLICT 都改为按它判定。
DROP INDEX IF EXISTS platform.uk_alert_event_system_key;

CREATE UNIQUE INDEX IF NOT EXISTS uk_alert_event_system_key
    ON platform.alert_event (event_key)
    WHERE source = 'SYSTEM';

CREATE INDEX IF NOT EXISTS idx_alert_event_source_created
    ON platform.alert_event (source, created_at DESC);

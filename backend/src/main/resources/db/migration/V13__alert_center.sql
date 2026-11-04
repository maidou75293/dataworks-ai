-- 告警中心：规则 + 事件
CREATE TABLE IF NOT EXISTS platform.alert_rule (
    id                bigserial PRIMARY KEY,
    project_id        bigint       NOT NULL REFERENCES platform.project(id) ON DELETE CASCADE,
    name              varchar(128) NOT NULL,
    rule_type         varchar(32)  NOT NULL, -- WORKFLOW_FAILED / QUALITY_FAILED / SLA_TIMEOUT
    workflow_id       bigint       REFERENCES platform.workflow(id) ON DELETE CASCADE,
    quality_rule_id   bigint       REFERENCES platform.quality_rule(id) ON DELETE CASCADE,
    threshold_minutes int,
    severity          varchar(16)  NOT NULL DEFAULT 'CRITICAL',
    enabled           boolean      NOT NULL DEFAULT true,
    created_by        varchar(64),
    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_at        timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_alert_rule_project ON platform.alert_rule (project_id, enabled);

CREATE TABLE IF NOT EXISTS platform.alert_event (
    id                bigserial PRIMARY KEY,
    rule_id           bigint       REFERENCES platform.alert_rule(id) ON DELETE SET NULL,
    rule_name         varchar(128) NOT NULL,
    project_id        bigint,
    rule_type         varchar(32)  NOT NULL,
    severity          varchar(16)  NOT NULL,
    title             varchar(255) NOT NULL,
    detail            text,
    event_key         varchar(128) NOT NULL,
    workflow_id       bigint,
    instance_id       bigint,
    quality_result_id bigint,
    status            varchar(16)  NOT NULL DEFAULT 'OPEN',
    created_at        timestamptz  NOT NULL DEFAULT now(),
    acked_by          varchar(64),
    acked_at          timestamptz,
    closed_by         varchar(64),
    closed_at         timestamptz
);

-- 同一规则对同一个对象只告警一次，轮询重复执行不会刷屏
CREATE UNIQUE INDEX IF NOT EXISTS uk_alert_event_rule_key
    ON platform.alert_event (rule_id, event_key);
CREATE INDEX IF NOT EXISTS idx_alert_event_status
    ON platform.alert_event (status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_alert_event_created
    ON platform.alert_event (created_at DESC);

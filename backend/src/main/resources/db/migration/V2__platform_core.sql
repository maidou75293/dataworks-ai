CREATE TABLE IF NOT EXISTS platform.project (
    id          bigserial PRIMARY KEY,
    code        varchar(64)  NOT NULL UNIQUE,
    name        varchar(128) NOT NULL,
    description text,
    created_at  timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS platform.datasource (
    id          bigserial PRIMARY KEY,
    project_id  bigint       NOT NULL REFERENCES platform.project(id) ON DELETE CASCADE,
    name        varchar(128) NOT NULL,
    ds_type     varchar(32)  NOT NULL DEFAULT 'POSTGRESQL',
    jdbc_url    text         NOT NULL,
    username    varchar(128) NOT NULL,
    password    text         NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS platform.workflow (
    id          bigserial PRIMARY KEY,
    project_id  bigint       NOT NULL REFERENCES platform.project(id) ON DELETE CASCADE,
    code        varchar(64)  NOT NULL,
    name        varchar(128) NOT NULL,
    env         varchar(16)  NOT NULL DEFAULT 'DEV',
    description text,
    model_json  jsonb        NOT NULL DEFAULT '{"tasks":[],"edges":[]}'::jsonb,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (project_id, code)
);

CREATE TABLE IF NOT EXISTS platform.workflow_version (
    id           bigserial PRIMARY KEY,
    workflow_id  bigint      NOT NULL REFERENCES platform.workflow(id) ON DELETE CASCADE,
    version_no   int         NOT NULL,
    model_json   jsonb       NOT NULL,
    status       varchar(24) NOT NULL DEFAULT 'DRAFT',
    created_by   varchar(64) NOT NULL DEFAULT 'admin',
    created_at   timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    published_by varchar(64),
    UNIQUE (workflow_id, version_no)
);

CREATE TABLE IF NOT EXISTS platform.schedule (
    id          bigserial PRIMARY KEY,
    workflow_id bigint      NOT NULL REFERENCES platform.workflow(id) ON DELETE CASCADE,
    env         varchar(16) NOT NULL DEFAULT 'DEV',
    cron        varchar(64) NOT NULL,
    timezone    varchar(64) NOT NULL DEFAULT 'Asia/Shanghai',
    status      varchar(16) NOT NULL DEFAULT 'OFFLINE',
    start_date  date,
    end_date    date,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (workflow_id, env)
);

CREATE TABLE IF NOT EXISTS platform.wf_instance (
    id           bigserial PRIMARY KEY,
    workflow_id  bigint      NOT NULL REFERENCES platform.workflow(id) ON DELETE CASCADE,
    version_id   bigint,
    dag_id       varchar(128) NOT NULL,
    run_id       varchar(128) NOT NULL,
    biz_date     date,
    state        varchar(32) NOT NULL DEFAULT 'queued',
    started_at   timestamptz,
    ended_at     timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (dag_id, run_id)
);

CREATE TABLE IF NOT EXISTS platform.task_instance (
    id             bigserial PRIMARY KEY,
    wf_instance_id bigint      NOT NULL REFERENCES platform.wf_instance(id) ON DELETE CASCADE,
    task_code      varchar(64) NOT NULL,
    task_name      varchar(128),
    state          varchar(32),
    try_number     int         NOT NULL DEFAULT 0,
    started_at     timestamptz,
    ended_at       timestamptz,
    log_text       text,
    updated_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (wf_instance_id, task_code)
);

CREATE TABLE IF NOT EXISTS platform.quality_rule (
    id          bigserial PRIMARY KEY,
    project_id  bigint       NOT NULL REFERENCES platform.project(id) ON DELETE CASCADE,
    code        varchar(64)  NOT NULL,
    name        varchar(128) NOT NULL,
    rule_type   varchar(32)  NOT NULL,
    table_name  varchar(256) NOT NULL,
    column_name varchar(128),
    config_json jsonb        NOT NULL DEFAULT '{}'::jsonb,
    enabled     boolean      NOT NULL DEFAULT true,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (project_id, code)
);

CREATE TABLE IF NOT EXISTS platform.quality_result (
    id         bigserial PRIMARY KEY,
    rule_id    bigint      NOT NULL REFERENCES platform.quality_rule(id) ON DELETE CASCADE,
    biz_date   date        NOT NULL,
    passed     boolean     NOT NULL,
    detail     text,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS platform.app_session (
    token      varchar(64) PRIMARY KEY,
    username   varchar(64) NOT NULL,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS platform.audit_log (
    id         bigserial PRIMARY KEY,
    username   varchar(64),
    action     varchar(64) NOT NULL,
    target     varchar(256),
    detail     text,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_wf_instance_workflow ON platform.wf_instance (workflow_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_quality_result_rule ON platform.quality_result (rule_id, biz_date DESC);

INSERT INTO platform.project (code, name, description)
VALUES ('demo', '示例项目', 'M1 验收用示例项目')
ON CONFLICT (code) DO NOTHING;

INSERT INTO platform.workflow (project_id, code, name, env, description)
SELECT id, 'demo_etl', '示例 ETL', 'DEV', '三节点验收工作流'
FROM platform.project WHERE code = 'demo'
ON CONFLICT (project_id, code) DO NOTHING;

INSERT INTO platform.quality_rule (project_id, code, name, rule_type, table_name, column_name, config_json)
SELECT id, 'demo_rowcount', '示例表行数大于 0', 'ROW_COUNT', 'platform.demo_metric', NULL,
       '{"min":1,"max":100000000}'::jsonb
FROM platform.project WHERE code = 'demo'
ON CONFLICT (project_id, code) DO NOTHING;

CREATE TABLE IF NOT EXISTS platform.demo_metric (
    id         bigserial PRIMARY KEY,
    biz_date   date        NOT NULL,
    metric     varchar(64) NOT NULL,
    value      numeric     NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

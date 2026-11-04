-- 数据服务：把只读 SQL 发布成带 Token 的 HTTP API
CREATE TABLE IF NOT EXISTS platform.data_service (
    id            bigserial PRIMARY KEY,
    project_id    bigint NOT NULL REFERENCES platform.project(id) ON DELETE CASCADE,
    code          varchar(64) NOT NULL,
    name          varchar(128) NOT NULL,
    method        varchar(8) NOT NULL DEFAULT 'GET',
    datasource_id bigint REFERENCES platform.datasource(id) ON DELETE RESTRICT,
    description   text,
    sql_text      text NOT NULL,
    params_json   text,
    status        varchar(16) NOT NULL DEFAULT 'DRAFT',
    version       int NOT NULL DEFAULT 1,
    api_token     varchar(64),
    created_by    varchar(64),
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    published_at  timestamptz,
    UNIQUE (project_id, code)
);

CREATE INDEX IF NOT EXISTS idx_data_service_project ON platform.data_service(project_id);

CREATE TABLE IF NOT EXISTS platform.data_service_call (
    id            bigserial PRIMARY KEY,
    service_id    bigint REFERENCES platform.data_service(id) ON DELETE CASCADE,
    service_code  varchar(64),
    caller        varchar(64),
    ip            varchar(64),
    status_code   int,
    rows_returned int,
    elapsed_ms    bigint,
    error         text,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ds_call_service ON platform.data_service_call(service_id, created_at DESC);

-- 安全中心：审计补充来源 IP / 结果 / 主体类型；会话补一个可吊销的 id
ALTER TABLE platform.audit_log ADD COLUMN IF NOT EXISTS ip varchar(64);
ALTER TABLE platform.audit_log ADD COLUMN IF NOT EXISTS result varchar(16) NOT NULL DEFAULT 'SUCCESS';
ALTER TABLE platform.audit_log ADD COLUMN IF NOT EXISTS actor_type varchar(16) NOT NULL DEFAULT 'USER';

CREATE INDEX IF NOT EXISTS idx_audit_created ON platform.audit_log(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_username ON platform.audit_log(username, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_action ON platform.audit_log(action, created_at DESC);

ALTER TABLE platform.app_session ADD COLUMN IF NOT EXISTS id bigserial;
CREATE INDEX IF NOT EXISTS idx_app_session_expires ON platform.app_session(expires_at);

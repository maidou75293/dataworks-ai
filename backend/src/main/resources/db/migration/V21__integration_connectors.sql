-- 数据集成连接器通用化 + 多模态语料元数据。
-- 旧任务保留 legacy 列；新任务使用 source_type/source_config、sink_type/sink_config。

ALTER TABLE platform.datasource
    ADD COLUMN IF NOT EXISTS config_json jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE platform.datasource ALTER COLUMN jdbc_url DROP NOT NULL;
ALTER TABLE platform.datasource ALTER COLUMN username DROP NOT NULL;
ALTER TABLE platform.datasource ALTER COLUMN password DROP NOT NULL;

ALTER TABLE platform.sync_task
    ADD COLUMN IF NOT EXISTS source_type varchar(64) NOT NULL DEFAULT 'Jdbc',
    ADD COLUMN IF NOT EXISTS source_config jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS sink_type varchar(64) NOT NULL DEFAULT 'Doris',
    ADD COLUMN IF NOT EXISTS sink_config jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS job_mode varchar(16) NOT NULL DEFAULT 'BATCH';

-- 非 JDBC 任务不再需要 legacy 连接列
ALTER TABLE platform.sync_task ALTER COLUMN source_jdbc_url DROP NOT NULL;
ALTER TABLE platform.sync_task ALTER COLUMN source_driver DROP NOT NULL;
ALTER TABLE platform.sync_task ALTER COLUMN source_user DROP NOT NULL;
ALTER TABLE platform.sync_task ALTER COLUMN source_password DROP NOT NULL;
ALTER TABLE platform.sync_task ALTER COLUMN source_query DROP NOT NULL;
ALTER TABLE platform.sync_task ALTER COLUMN sink_fenodes DROP NOT NULL;
ALTER TABLE platform.sync_task ALTER COLUMN sink_database DROP NOT NULL;
ALTER TABLE platform.sync_task ALTER COLUMN sink_table DROP NOT NULL;
ALTER TABLE platform.sync_task ALTER COLUMN sink_user DROP NOT NULL;
ALTER TABLE platform.sync_task ALTER COLUMN sink_password DROP NOT NULL;

-- 旧任务回填 config；密码仍在 legacy 列，运行时合并
UPDATE platform.sync_task
SET source_config = jsonb_build_object(
    'url', source_jdbc_url,
    'driver', source_driver,
    'user', source_user,
    'query', source_query
)
WHERE source_config = '{}'::jsonb;

UPDATE platform.sync_task
SET sink_config = jsonb_build_object(
    'fenodes', sink_fenodes,
    'database', sink_database,
    'table', sink_table,
    'user', sink_user
)
WHERE sink_config = '{}'::jsonb;

CREATE TABLE IF NOT EXISTS platform.multimodal_asset (
    id            bigserial PRIMARY KEY,
    project_id    bigint NOT NULL REFERENCES platform.project(id) ON DELETE CASCADE,
    dataset       varchar(128) NOT NULL,
    asset_type    varchar(16) NOT NULL
        CHECK (asset_type IN ('IMAGE', 'AUDIO', 'VIDEO', 'DOCUMENT')),
    uri           text NOT NULL,
    file_name     varchar(255),
    mime_type     varchar(128),
    size_bytes    bigint,
    sha256        varchar(64),
    width         integer,
    height        integer,
    duration_ms   bigint,
    page_count    integer,
    language      varchar(16),
    source_uri    text,
    captured_at   timestamptz,
    tags          text[] NOT NULL DEFAULT '{}',
    metadata      jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_multimodal_asset_project
    ON platform.multimodal_asset (project_id, dataset, asset_type);
CREATE INDEX IF NOT EXISTS idx_multimodal_asset_sha
    ON platform.multimodal_asset (sha256);

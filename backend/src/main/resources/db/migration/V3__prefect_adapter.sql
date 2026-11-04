ALTER TABLE platform.workflow_version
    ADD COLUMN IF NOT EXISTS prefect_deployment_id text,
    ADD COLUMN IF NOT EXISTS prefect_flow_name text,
    ADD COLUMN IF NOT EXISTS prefect_flow_file text,
    ADD COLUMN IF NOT EXISTS prefect_env varchar(16);

CREATE INDEX IF NOT EXISTS idx_wf_version_prefect_deployment
    ON platform.workflow_version (prefect_deployment_id);

-- 调度类型：一次性 / 每天 / 每周几 / 每月几号；新字段为空时沿用 cron。
ALTER TABLE platform.schedule
    ADD COLUMN IF NOT EXISTS schedule_type varchar(16) NOT NULL DEFAULT 'CRON',
    ADD COLUMN IF NOT EXISTS run_time varchar(8),
    ADD COLUMN IF NOT EXISTS weekdays varchar(32),
    ADD COLUMN IF NOT EXISTS month_days varchar(128),
    ADD COLUMN IF NOT EXISTS start_at timestamptz;

CREATE INDEX IF NOT EXISTS idx_schedule_workflow_env ON platform.schedule(workflow_id, env);

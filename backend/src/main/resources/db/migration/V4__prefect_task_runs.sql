ALTER TABLE platform.task_instance
    ADD COLUMN IF NOT EXISTS prefect_task_run_id varchar(64);

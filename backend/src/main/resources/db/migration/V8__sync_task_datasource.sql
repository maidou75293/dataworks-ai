-- 数据集成改为引用「数据源管理」里的数据源；旧任务保留手填连接串作为回退。
ALTER TABLE platform.sync_task
    ADD COLUMN IF NOT EXISTS source_datasource_id bigint
        REFERENCES platform.datasource(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS sink_datasource_id bigint
        REFERENCES platform.datasource(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_sync_task_source_ds ON platform.sync_task(source_datasource_id);
CREATE INDEX IF NOT EXISTS idx_sync_task_sink_ds ON platform.sync_task(sink_datasource_id);

-- 增量同步水位：每个任务、每张源表保存上次成功同步的游标值。
-- 只在平台判定作业 SUCCESS 后推进；失败不推进，重跑天然幂等。
create table if not exists platform.sync_task_watermark (
    task_id bigint not null references platform.sync_task (id) on delete cascade,
    table_name text not null,
    cursor_column text not null,
    watermark_value text,
    updated_at timestamptz not null default now(),
    primary key (task_id, table_name)
);

comment on table platform.sync_task_watermark is '增量同步水位（按任务+源表）';

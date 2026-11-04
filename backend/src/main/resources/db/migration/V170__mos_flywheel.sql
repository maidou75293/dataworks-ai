-- REQ-FW-2 飞轮②产物回环：中台侧模型产物台账 + 「由中台发起」的 mos job 跟踪 + 运行事件。
-- 台账字段 = 契约 §4.2（labelstudio-ms-swift-contract.md）：ext_id 是对方 mdl_xxx 字符串主键，
-- 禁止按数字处理（契约 §4.2 已实证数字必 404）。INT-5 路线已 CANCELED，本表即唯一台账表（卡面边界：不建第二张）。

create table if not exists platform.mos_model_ledger (
    id              bigserial primary key,
    ext_id          varchar(40)  not null unique,           -- 对方 models.id（mdl_xxx）
    name            text         not null,
    base            text,                                    -- 基座模型
    method          text,                                    -- 对方 models.method（export/quant/...）
    dataset_ref     text,                                    -- dataset_id@version（契约 §4.2）
    job_ext_id      varchar(40),                             -- 产出该物的对方 jobs.id
    artifact_type   varchar(16),                             -- adapter | merged | awq（契约 §2.2 三形态）
    manifest_digest varchar(64),                             -- sha256(GET /models/{id}/manifest 原文)
    size_gb         double precision,
    mos_status      varchar(16),                             -- 对方侧 draft/published
    path_or_key     text,                                    -- 对方目录路径或 MinIO 对象 key
    register_source varchar(32)  not null default 'auto:fw2',
    eval_ext_id     varchar(40),                             -- 最近一次写回分数的评测 id（ev_xxx）
    eval_score      double precision,                        -- result.summary.avg（0-10 裁判均分）
    eval_status     varchar(16),                             -- done | failed
    tenant_id       varchar(64)  not null default 'default',
    project_id      bigint,
    created_by      varchar(64),
    first_seen_at   timestamptz  not null default now(),
    updated_at      timestamptz  not null default now()
);

create index if not exists idx_mos_ledger_tenant
    on platform.mos_model_ledger (tenant_id, first_seen_at desc);
create index if not exists idx_mos_ledger_job
    on platform.mos_model_ledger (job_ext_id);

-- 跟踪表：只轮询「由中台发起」的 job/eval（契约 §5：轮询 ≥30s、以 updated_at 判增量、指数退避）。
create table if not exists platform.mos_job_watch (
    id            bigserial primary key,
    kind          varchar(8)   not null,                    -- job（训练）| eval（评测）
    ext_id        varchar(40)  not null,                    -- 对方 jobs.id / evals.id
    status        varchar(16)  not null default 'queued',   -- 最近一次观测到的聚合态
    terminal      boolean      not null default false,      -- finished/failed/stopped（job）或 done/failed（eval）
    last_seen_at  text,                                     -- 对方 updated_at 原文（增量判据）
    next_poll_at  timestamptz  not null default now(),
    attempts      integer      not null default 0,          -- 连续取回失败的次数（退避指数）
    last_error    text,
    payload       jsonb,                                    -- eval：[{"candidate":名,"extId":mdl_xxx}] 候选→台账映射
    tenant_id     varchar(64)  not null default 'default',
    project_id    bigint,
    created_by    varchar(64),
    created_at    timestamptz  not null default now(),
    updated_at    timestamptz  not null default now(),
    constraint uk_mos_watch_kind_ext unique (kind, ext_id)
);

create index if not exists idx_mos_watch_due
    on platform.mos_job_watch (terminal, next_poll_at);

-- 终态事件入运行事件。模式参照 integration/RunEventService.java：
-- 写事件失败只记日志、不影响主流程；但表必须独立——sync_run_event.run_id 外键锁死同步域。
create table if not exists platform.mos_run_event (
    id      bigserial primary key,
    watch_id bigint      not null references platform.mos_job_watch (id) on delete cascade,
    ext_id  varchar(40)  not null,
    ts      timestamptz  not null default now(),
    phase   varchar(16)  not null,                          -- progress | terminal | register | eval
    type    varchar(32)  not null,                          -- INFO | WARN
    message text,
    metrics jsonb
);

create index if not exists idx_mos_event_watch
    on platform.mos_run_event (watch_id, id);

-- 权限点：mos:flywheel（发起跟踪 + 自动登记台账）。与 REQ-UNIFY-1 的 mos:access 语义不同：
-- access = 打开 mos 代理通道（读对方页面），flywheel = 中台发起训练跟踪并把产物自动登记进中台台账（写）。
-- 两卡在不同分支（UNIFY-1 在 wm6/V150，本卡在 wm9/V170），各用独立点合并时不撞、不复用语义。
insert into platform.permission_catalog (code, name, group_name, danger) values
  ('mos:flywheel', '训练产物自动回环登记', '模型微调', '中')
on conflict (code) do update set name = excluded.name,
    group_name = excluded.group_name, danger = excluded.danger;

-- 授予面与 V150 同口径：内置 ADMIN + DEVELOPER（其余角色在角色页按需勾选；
-- 新租户由 RbacService.createTenant 从默认租户内置角色复制 role_permission，无需另行处理）。
insert into platform.role_permission (role_id, permission)
select r.id, 'mos:flywheel'
from platform.role r
where r.builtin and r.code in ('DEVELOPER', 'ADMIN')
on conflict (role_id, permission) do nothing;

-- REQ-NIGHT-LEARN-1 学习：岗位（=1 个标注项目）夜学配置 + 一夜一岗晨报台账。
-- 口径（docs/学习-20261104.md，项目负责人信 ③/24④c）：
--   全自动=夜里逐岗自动一圈 FlywheelRun，晨复核=异常圈单列等人看；项目负责人零操作。
-- 号段：现最大 V211（REQ-OSS-ENGINE-1），本卡取 V212。
--
-- 岗位主键用 label_project_id（MOS 标注项目号）而不是平台项目号：
--   flywheel_run.project_id 是平台项目（Doris 路由 + TenantGuard 口径），
--   拿标注项目号塞进去会被 by-id 租户守卫判越权。故岗位身份=标注项目号，
--   平台项目另存 platform_project_id（可空=沿用后端现网默认路由），开圈时作 run 的 projectId。

create table if not exists platform.flywheel_night_post (
    label_project_id     bigint primary key,                       -- 岗位身份=标注项目
    label_project_title  text,                                     -- 冗余存标题，晨报/岗位清单免二跳
    tenant_id            varchar(64)  not null default 'default',
    platform_project_id  bigint,                                   -- Doris 路由项目（可空）
    enabled              boolean not null default false,           -- 夜间学习开关（配置入口=标注项目页）
    window_start         varchar(5)  not null default '00:00',     -- 夜间窗起，HH:MM
    window_end           varchar(5)  not null default '06:00',     -- 夜间窗止，HH:MM（早于起=跨零点）
    eval_min_score       numeric(4,1) not null default 7.0,        -- 岗位准入线（0–10，卡面「用岗位配置」）
    feature_sql          text,                                     -- 选表入环 SQL（开圈 spec.sql）
    q_column             varchar(128),                             -- 问题列
    a_column             varchar(128),                             -- 答案列
    ref_column           varchar(128),                             -- 引用列（可空）
    max_rows             int not null default 200,
    updated_by           varchar(64),
    updated_at           timestamptz not null default now(),
    check (eval_min_score >= 0 and eval_min_score <= 10),
    check (max_rows between 1 and 500)                             -- 分页契约 size 1–500
);

create index if not exists idx_night_post_tenant
    on platform.flywheel_night_post (tenant_id, enabled, label_project_id);

-- 晨报台账：一夜一岗一行。引擎重报=覆盖（不新增行），所以唯一键是业务键而不是时间戳。
create table if not exists platform.flywheel_night_report (
    id                 bigserial primary key,
    night_date         date not null,                              -- 归属「开窗那天」（跨零点窗仍算同一夜）
    label_project_id   bigint not null,
    label_project_title text,
    tenant_id          varchar(64) not null default 'default',
    window_start       varchar(5),
    window_end         varchar(5),
    runs               int not null default 0,                     -- PRD 晨报项①圈数
    passed             int not null default 0,                     -- PRD 晨报项②通过数
    abnormal           int not null default 0,                     -- 异常圈数（单列，晨复核入口）
    badcase_top        jsonb not null default '[]'::jsonb,         -- PRD 晨报项③badcase TOP（[{question,count}]）
    new_models         jsonb not null default '[]'::jsonb,         -- PRD 晨报项④新模型版本清单
    abnormal_runs      jsonb not null default '[]'::jsonb,         -- [{runId,kind,reason,step}]
    detail             jsonb not null default '{}'::jsonb,         -- 闸痕/最后状态/badcase 面可用性
    source             varchar(64),                                -- cc-ctrld/postlearn（人工补写=别的来源说得清）
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now(),
    unique (night_date, label_project_id)
);

create index if not exists idx_night_report_tenant_date
    on platform.flywheel_night_report (tenant_id, night_date desc, label_project_id);

comment on table platform.flywheel_night_post is '夜间岗位学习配置（1 标注项目=1 岗位）——REQ-NIGHT-LEARN-1';
comment on table platform.flywheel_night_report is '夜间学习晨报台账，一夜一岗一行，异常圈单列——REQ-NIGHT-LEARN-1';

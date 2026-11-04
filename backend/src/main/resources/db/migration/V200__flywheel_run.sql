-- REQ-FW-4 无缝编排：一圈一记录的飞轮运行台账 + 步事件流水。
-- 口径（卡面 03:03）：编排以 mos 原生链为主（runner._auto_chain + webhook/对账），
-- 中台 Orchestrator 只做「发起+记账+闸门」——本表就是「记账」的唯一落点，
-- 链内步骤（标注队列/冻结快照/训练阶段链/评测运行/推理实例）一律不复制状态。
--
-- 步骤链（current_step，与卡面 7 步一一对应）：
--   TABLE 选表入环(FW-1 push) → ANNOTATE 标注任务(闸门A) → TRAIN 训练job(FW-2 watch)
--   → EVAL 评测(闸门B) → DEPLOY 部署 → BADCASE 池(FW-3 采集) → RELABEL 下一圈(回环台账)
-- 闸门只有两个（卡面「人工只保留标注完成与评测准入」）：status=WAIT_GATE + gate_wait 指明等哪个。
--
-- 号段：现最大 V190（FW-5 改号后），FW-4 取 V200（卡面未指定，号段与 FW-1..5 均不重叠）。

create table if not exists platform.flywheel_run (
    id               bigserial primary key,
    tenant_id        varchar(64)  not null default 'default',
    project_id       bigint,
    parent_run_id    bigint references platform.flywheel_run (id),  -- 下一圈子运行指回上一圈
    status           varchar(16)  not null check (status in ('RUNNING','WAIT_GATE','CLOSED','FAILED')),
    current_step     varchar(32)  not null check (current_step in
                           ('TABLE','ANNOTATE','TRAIN','EVAL','DEPLOY','BADCASE','RELABEL')),
    gate_wait        varchar(16),                                   -- ANNOTATE | EVAL（仅 WAIT_GATE 有值）
    spec             jsonb        not null,                         -- {mode,sql,maxRows,qColumn,aColumn,refColumn,base,method,chainExport,chainQuant,evalLimit,candidateBaseUrl}
    ds_id            varchar(64),                                   -- FW-1 进环数据集（对方主键 ds_xxx）
    dataset_version  varchar(32),                                   -- 冻结后读回的版本（闸门A 放行动作产物）
    job_ext_id       varchar(40),                                   -- FW-2 跟踪的训练 job（mdl 按此反查台账）
    eval_ext_id      varchar(40),                                   -- 评测 id（FW-2 跟踪，分数由 auto:fw2 写回台账）
    model_ext_id     varchar(40),                                   -- 台账产物 ext_id（契约 §4.2 字符串主键）
    model_name       text,                                          -- 候选名/部署描述用
    deploy_ref       varchar(64),                                   -- mos 侧部署实例 id（dep_xxx）
    deploy_ts        timestamptz,                                   -- 部署完成时刻——BADCASE/RELABEL 观察窗下界
    relabel_batch_id bigint,                                        -- FW-3 回环批次（flywheel_relabel_batch.id）
    relabel_task_ref varchar(128),                                  -- 批次 PUSHED 后的 mos 任务引用
    child_run_id     bigint,                                        -- auto_renew 生成的下一圈
    last_error       text,
    created_by       varchar(64),
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    finished_at      timestamptz
);

create index if not exists idx_flywheel_run_tenant_status
    on platform.flywheel_run (tenant_id, status, id desc);

-- 步事件流水（泳道图「当前卡在哪一步」的悬停证据 + e2e 断言面）。
-- 与 mos_run_event（FW-2，按 watch 分组）语义不同：本表按 run 分组，记编排衔接点。
create table if not exists platform.flywheel_run_event (
    id      bigserial primary key,
    run_id  bigint not null references platform.flywheel_run (id) on delete cascade,
    step    varchar(32) not null,
    type    varchar(16) not null check (type in ('INFO','WARN','ERROR','GATE')),
    message text,
    metrics jsonb,
    ts      timestamptz not null default now()
);

create index if not exists idx_flywheel_run_event_run
    on platform.flywheel_run_event (run_id, id);

-- 编排运行参数（platform.setting=本仓 sys_config 惯例，口径同 FlywheelSettings/V180）。
-- 两个闸门默认 MANUAL（质量闸默认要人），配 AUTO 即「自动放行」——卡面要求可配置。
insert into platform.setting (setting_key, setting_value, updated_by)
values ('flywheel.orch.annotation_gate', 'MANUAL', 'migration'),
       ('flywheel.orch.eval_gate', 'MANUAL', 'migration'),
       ('flywheel.orch.eval_min_score', '7.0', 'migration'),
       ('flywheel.orch.auto_renew', 'true', 'migration')
on conflict (setting_key) do nothing;

comment on table platform.flywheel_run is
    '数据飞轮一圈一记录（REQ-FW-4）：表→标注→job→评测→部署→badcase→下一圈 relabel 的编排台账';

-- TC7-G5 审批链可配置 + 会签/或签 + 超时升级（对标 DataWorks 审批中心「自定义审批策略」）
-- 策略 = 每个租户按数据密级（L1–L4）定义 1–2 级审批节点；节点模式 ANY=或签（任一人）/
-- ALL=会签（池内全部签完才过，申请人自动排除出应签池）；timeout_days>0 时该级停留超时
-- 由 AccessEscalationJob 升级：把 escalate_to 并入当级可签池并发提醒。
-- 取不到租户策略时回退 default 租户行，再取不到＝旧硬编码（非 L4 单级、L4 固定双人）。
-- 中间态仍复用 PENDING/PENDING_SECOND 两个既有状态（级号由 status 推导），所以旧列表/
-- 审计/回归断言零改动；多级扩展位留给 policy_steps.steps，超过 2 级时再动状态枚举。
create table if not exists platform.access_approval_policy (
    id           bigserial   primary key,
    tenant_id    varchar(64) not null,
    data_level   varchar(8)  not null,
    steps        jsonb       not null,
    timeout_days int         not null default 0,
    escalate_to  jsonb       not null default '[]'::jsonb,
    enabled      boolean     not null default true,
    updated_by   varchar(64),
    updated_at   timestamptz not null default now(),
    constraint access_approval_policy_level
        check (data_level in ('L1', 'L2', 'L3', 'L4')),
    constraint access_approval_policy_timeout
        check (timeout_days between 0 and 365),
    constraint access_approval_policy_udx
        unique (tenant_id, data_level)
);

-- 申请行上的审批链快照：提单时冻结（改策略不回溯在途单，主流审批中心口径）
alter table platform.access_request
    add column if not exists policy_steps jsonb;
-- 当前级已会签人数（进下一级时清空；或签模式一人签完即推进，不依赖此列）
alter table platform.access_request
    add column if not exists step_signed jsonb not null default '[]'::jsonb;
-- 进入当前级的时间（超时升级的计时起点；进下一级时刷新）
alter table platform.access_request
    add column if not exists step_at timestamptz not null default now();
-- 已升级到的级号（-1/NULL=未升级；同级只升级一次，幂等锚点）
alter table platform.access_request
    add column if not exists escalated_step int;

create index if not exists idx_access_request_policy_pending
    on platform.access_request (step_at)
    where policy_steps is not null and status in ('PENDING', 'PENDING_SECOND');

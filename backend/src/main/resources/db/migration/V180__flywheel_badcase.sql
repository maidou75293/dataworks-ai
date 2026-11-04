-- REQ-FW-3：数据飞轮③ badcase 回流——低质样本采集 → 成池 → 批量阈值自动建 mos 重标注任务。
-- 三段对应：usage_query_event 信号列（采集面）、badcase_pool（成池面）、
-- flywheel_relabel_batch（回环台账）。实现 = backend/com.lab.dataworks.flywheel。

-- 0) 采集源补信号列。V121 的计量口径列不动、既有写入点（UsageMeteringService，POST /api/query/doris
--    成功路径）不受影响；新列对老行 = NULL，采集规则把「无 question 的事件」按不可成池跳过并计数。
--    部署服务/助手问答侧从本迁移起可按这四列回写低质信号：
--    question=原始提问文本，feedback='DOWN'/'UP'（点踩），confidence=模型自评置信度，answer_model=出答模型名。
alter table platform.usage_query_event add column if not exists question      text;
alter table platform.usage_query_event add column if not exists feedback      varchar(16);
alter table platform.usage_query_event add column if not exists confidence    numeric(6,4);
alter table platform.usage_query_event add column if not exists answer_model  varchar(128);

-- 1) 低质样本池。去重不上向量、参照 text-similarity 的简化口径=归一化问题 sha256：
--    同租户同轮（status='NEW' 未批量窗口）同 query 只进一次；批次推给 mos 后置 PUSHED，
--    下一圈允许重新收同类新样本（FW-1 的 ds_id@version 幂等台账接住版本推进）。
create table if not exists platform.badcase_pool (
    id              bigserial primary key,
    tenant_id       varchar(64) not null,
    project_id      bigint,
    source_event_id bigint      not null,
    question        text        not null,
    question_hash   varchar(64) not null,
    signal          varchar(16) not null check (signal in ('NO_ANSWER','DISLIKE','LOW_CONFIDENCE')),
    confidence      numeric(6,4),
    answer_model    varchar(128),
    status          varchar(16) not null default 'NEW' check (status in ('NEW','BATCHED','PUSHED')),
    batch_id        bigint,
    created_at      timestamptz not null default now()
);

create index if not exists idx_badcase_pool_tenant_status
    on platform.badcase_pool (tenant_id, status, id);

-- 同一源事件只成池一次（重放扫描不重复入库）；同 query 同轮只进一次（部分唯一索引）
create unique index if not exists uq_badcase_pool_event
    on platform.badcase_pool (source_event_id);
create unique index if not exists uq_badcase_pool_open_query
    on platform.badcase_pool (tenant_id, question_hash) where status = 'NEW';

-- 2) 回环台账（badcase→relabel 链）。ledger_key='badcase:<tenant>:<round>' 全局唯一，
--    READY=池已封批、等桥接（FW-1 mosbridge 的 MosRelabelGateway 实现接入后即推）；
--    PUSHED=对方任务号已回写。推失败不置终态、留 last_error 下圈重试（旁路任务不能一抖一死）。
create table if not exists platform.flywheel_relabel_batch (
    id           bigserial primary key,
    tenant_id    varchar(64)  not null,
    ledger_key   varchar(128) not null unique,
    round_no     bigint       not null,
    item_count   int          not null,
    status       varchar(16)  not null default 'READY' check (status in ('READY','PUSHED')),
    mos_task_ref varchar(128),
    last_error   text,
    created_at   timestamptz  not null default now(),
    pushed_at    timestamptz
);

create index if not exists idx_relabel_batch_tenant_round
    on platform.flywheel_relabel_batch (tenant_id, round_no);

-- 3) 可调参数进 platform.setting（V58 的通用运行时设置表=本仓 sys_config 惯例），默认不做死。
insert into platform.setting (setting_key, setting_value, updated_by)
values ('flywheel.badcase.confidence_threshold', '0.6', 'migration'),
       ('flywheel.badcase.batch_size', '50', 'migration'),
       ('flywheel.badcase.cursor_id', '0', 'migration'),
       ('flywheel.relabel.target_model', '', 'migration')
on conflict (setting_key) do nothing;

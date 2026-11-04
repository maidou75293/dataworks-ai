-- 会话滚动摘要 + 类型化记忆（2026-10-23）
--
-- 会话不再固定注入最近 20 条：超过阈值后把更早的消息压成 summary，
-- 模型只看到「摘要 + 最近原文」。记忆增加类型与使用统计，召回时优先
-- DECISION/RULE，其次 FACT/PREFERENCE。

alter table platform.agent_conversation
    add column if not exists summary text;
alter table platform.agent_conversation
    add column if not exists summary_upto_seq integer not null default 0;

alter table platform.agent_memory
    add column if not exists mem_type varchar(16) not null default 'FACT';
alter table platform.agent_memory
    add column if not exists use_count integer not null default 0;
alter table platform.agent_memory
    add column if not exists last_used_at timestamptz;

do $$
begin
    if not exists (
        select 1 from pg_constraint where conname = 'agent_memory_type'
    ) then
        alter table platform.agent_memory
            add constraint agent_memory_type
            check (mem_type in ('FACT', 'PREFERENCE', 'DECISION', 'RULE'));
    end if;
end $$;

create index if not exists idx_agent_memory_recall
    on platform.agent_memory (tenant_id, scope, owner, mem_type, use_count desc);

comment on column platform.agent_conversation.summary is
    '更早对话的滚动摘要；summary_upto_seq 之前的消息不再逐条注入';
comment on column platform.agent_memory.mem_type is
    'FACT 事实 / PREFERENCE 偏好 / DECISION 决策 / RULE 口径规则';

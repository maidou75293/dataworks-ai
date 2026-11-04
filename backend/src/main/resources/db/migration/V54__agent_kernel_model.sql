-- 内核模型 v0（三方标准）：memory 只装 semantic / procedural / profile 三类，
-- 会话与摘要留在会话表；authority 由 kind 派生不落库；枚举机器值一律小写。
--
-- 存储与 Port 解耦：列名保持 owner / mem_key / mem_value，出参在 controller 里映射成
-- scope_id / key / content，避免重命名带来的全量 SQL 改写。
-- 唯一约束改成 partial unique（只看 status='active'），支持只追加 + supersede 失效。

alter table platform.project add column if not exists tenant_id varchar(64) not null default 'default';
create index if not exists idx_project_tenant on platform.project (tenant_id);

alter table platform.agent_memory add column if not exists kind varchar(16) not null default 'semantic';
alter table platform.agent_memory add column if not exists subtype varchar(32) not null default '';
alter table platform.agent_memory add column if not exists condition varchar(64) not null default '';
alter table platform.agent_memory add column if not exists summary text not null default '';
alter table platform.agent_memory add column if not exists outcome text not null default '';
alter table platform.agent_memory add column if not exists importance real not null default 0.5;
alter table platform.agent_memory add column if not exists confidence real not null default 0.6;
alter table platform.agent_memory add column if not exists status varchar(16) not null default 'active';
alter table platform.agent_memory add column if not exists status_reason varchar(32) not null default '';
alter table platform.agent_memory add column if not exists superseded_by varchar(64) not null default '';
alter table platform.agent_memory add column if not exists content_hash varchar(64) not null default '';
alter table platform.agent_memory add column if not exists version integer not null default 1;
alter table platform.agent_memory add column if not exists agent_id varchar(64) not null default '';
alter table platform.agent_memory add column if not exists session_id varchar(64) not null default '';

-- 存量迁移：mem_type 拆成 kind + subtype，scope 转小写（SYSTEM→platform）
update platform.agent_memory
   set kind = case mem_type
                when 'RULE' then 'procedural'
                when 'PREFERENCE' then 'profile'
                else 'semantic'
              end,
       subtype = case mem_type
                   when 'RULE' then 'policy'
                   when 'PREFERENCE' then 'preference'
                   when 'DECISION' then 'decision'
                   else 'fact'
                 end,
       scope = case scope
                 when 'SYSTEM' then 'platform'
                 when 'PROJECT' then 'project'
                 when 'USER' then 'user'
                 else lower(scope)
               end,
       content_hash = encode(sha256(convert_to(mem_value, 'UTF8')), 'hex')
 where true;

alter table platform.agent_memory drop constraint if exists agent_memory_scope;
alter table platform.agent_memory drop constraint if exists agent_memory_type;
alter table platform.agent_memory drop constraint if exists agent_memory_tenant_id_scope_owner_mem_key_key;
drop index if exists platform.idx_agent_memory_scope;
drop index if exists platform.idx_agent_memory_recall;
drop index if exists platform.idx_agent_memory_system;

alter table platform.agent_memory drop column if exists mem_type;

alter table platform.agent_memory
  add constraint agent_memory_scope check (scope in ('platform', 'project', 'user', 'session'));
alter table platform.agent_memory
  add constraint agent_memory_kind check (kind in ('semantic', 'procedural', 'profile'));
alter table platform.agent_memory
  add constraint agent_memory_status check (status in ('active', 'superseded', 'archived', 'deleted'));

-- R1：同一 (tenant, scope, scope_id, agent_id, key, condition) 只有一条 active
--     agent_id 默认空串，单 agent 产品行为与之前一致
create unique index if not exists uq_agent_memory_active
    on platform.agent_memory (tenant_id, scope, owner, agent_id, mem_key, condition)
    where status = 'active';

create index if not exists idx_agent_memory_scope
    on platform.agent_memory (tenant_id, scope, owner, status);
create index if not exists idx_agent_memory_recall
    on platform.agent_memory (tenant_id, scope, owner, kind, status, use_count desc);
create index if not exists idx_agent_memory_platform
    on platform.agent_memory (tenant_id, scope, kind, status);

comment on column platform.agent_memory.kind is 'semantic/procedural/profile；authority 由它派生，不落库';
comment on column platform.agent_memory.condition is '断言条件（如 DEV/PROD）；同 key 同 condition 才冲突';
comment on column platform.agent_memory.status_reason is 'user_retracted/expired/superseded/policy';

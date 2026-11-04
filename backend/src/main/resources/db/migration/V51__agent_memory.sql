-- Agent 长期记忆：会话、消息、任务草稿、用户/项目事实（2026-10-23）
--
-- 之前会话与槽位只在进程/前端内存里，刷新或重启即丢。这里全部落平台库：
--   agent_conversation  一次对话（session_id 与前端一致）
--   agent_message       对话消息（含工具调用摘要）
--   agent_task_draft    M2 建任务槽位（按会话续聊）
--   agent_memory        用户/项目级显式事实（owner 是用户名或项目 id）

create table if not exists platform.agent_conversation (
    id              bigserial primary key,
    session_id      varchar(64)  not null unique,
    tenant_id       varchar(64)  not null default 'default',
    username        varchar(128) not null,
    project_id      bigint references platform.project(id) on delete set null,
    title           varchar(200),
    status          varchar(16)  not null default 'ACTIVE',
    created_at      timestamptz  not null default now(),
    updated_at      timestamptz  not null default now(),
    last_message_at timestamptz
);

create index if not exists idx_agent_conversation_owner
    on platform.agent_conversation (tenant_id, username, last_message_at desc);

create table if not exists platform.agent_message (
    id         bigserial primary key,
    session_id varchar(64) not null
               references platform.agent_conversation(session_id) on delete cascade,
    seq        integer     not null,
    role       varchar(16) not null,
    content    text,
    tool_calls jsonb,
    status     varchar(16),
    created_at timestamptz not null default now(),
    unique (session_id, seq),
    constraint agent_message_role check (role in ('user', 'assistant', 'system', 'tool'))
);

create index if not exists idx_agent_message_session
    on platform.agent_message (session_id, seq);

create table if not exists platform.agent_task_draft (
    session_id varchar(64) primary key
               references platform.agent_conversation(session_id) on delete cascade,
    tenant_id  varchar(64) not null default 'default',
    username   varchar(128) not null,
    project_id bigint,
    stage      varchar(32),
    slots      jsonb not null default '{}'::jsonb,
    plan       text,
    updated_at timestamptz not null default now()
);

create table if not exists platform.agent_memory (
    id         bigserial primary key,
    tenant_id  varchar(64)  not null default 'default',
    scope      varchar(16)  not null,
    owner      varchar(64)  not null,
    mem_key    varchar(128) not null,
    mem_value  text         not null,
    source     varchar(16)  not null default 'USER',
    expires_at timestamptz,
    created_by varchar(64),
    created_at timestamptz  not null default now(),
    updated_at timestamptz  not null default now(),
    unique (tenant_id, scope, owner, mem_key),
    constraint agent_memory_scope check (scope in ('USER', 'PROJECT'))
);

create index if not exists idx_agent_memory_scope
    on platform.agent_memory (tenant_id, scope, owner);

comment on table platform.agent_conversation is 'Agent 对话：刷新/重启后可续聊';
comment on table platform.agent_task_draft is 'M2 建任务槽位，按会话持久化';
comment on table platform.agent_memory is '显式记忆：用户偏好 / 项目口径，owner 为用户名或项目 id';

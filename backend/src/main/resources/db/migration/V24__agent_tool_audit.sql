-- Agent 工具调用审计：所有只读工具调用必须能追溯到租户、用户、会话。
create table if not exists platform.agent_tool_audit (
    id bigserial primary key,
    tenant_id varchar(64) not null default 'default',
    username varchar(128) not null,
    session_id varchar(64),
    tool varchar(64) not null,
    args json,
    ok boolean not null default false,
    duration_ms integer,
    result_summary text,
    created_at timestamptz not null default now()
);

create index if not exists idx_agent_tool_audit_user
    on platform.agent_tool_audit (tenant_id, username, created_at desc);

comment on table platform.agent_tool_audit is 'Agent 工具调用审计（只读工具先行）';

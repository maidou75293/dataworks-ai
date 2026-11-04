-- Agent / MCP 的变更申请：删除、改配置、权限、调度、表结构变更只落申请，不直接执行。
create table if not exists platform.agent_change_request (
    id           bigserial primary key,
    tenant_id    varchar(64)  not null default 'default',
    requester    varchar(128) not null,
    session_id   varchar(128),
    request_type varchar(32)  not null,
    target_type  varchar(32)  not null,
    target_id    bigint,
    target_name  varchar(256),
    reason       text         not null,
    impact       text,
    rollback     text,
    status       varchar(32)  not null default 'PENDING_APPROVAL',
    created_at   timestamptz  not null default now()
);
create index if not exists idx_agent_change_request_status
    on platform.agent_change_request (status, created_at desc);

-- 权限申请事件（v473 F-P1-2）：审批/回收等动作追加留痕，原行审批字段不再被覆盖。
-- access_request 行上的 approver/comment/decided_at 保持首次审批原样；
-- 后续回收只改 status/revoked_by/revoked_at，并在本表追加 REVOKE 事件。
create table if not exists platform.access_request_event (
    id         bigserial   primary key,
    request_id bigint      not null references platform.access_request (id) on delete cascade,
    action     varchar(32) not null,
    actor      varchar(64),
    detail     text,
    created_at timestamptz not null default now()
);

create index if not exists idx_access_request_event_request
    on platform.access_request_event (request_id, created_at);

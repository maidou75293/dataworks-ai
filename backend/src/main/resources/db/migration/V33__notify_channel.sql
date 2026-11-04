-- 告警通知渠道（Webhook）与投递记录。
create table if not exists platform.notify_channel (
    id         bigserial primary key,
    project_id bigint references platform.project (id) on delete cascade,
    name       varchar(128)  not null,
    type       varchar(16)   not null default 'WEBHOOK',
    url        varchar(1024) not null,
    secret     varchar(256),
    enabled    boolean       not null default true,
    created_at timestamptz   not null default now()
);

create table if not exists platform.alert_delivery (
    id          bigserial primary key,
    event_id    bigint      not null references platform.alert_event (id) on delete cascade,
    channel_id  bigint      not null references platform.notify_channel (id) on delete cascade,
    status      varchar(16) not null,
    http_status integer,
    response    varchar(500),
    created_at  timestamptz not null default now(),
    unique (event_id, channel_id)
);

create index if not exists idx_alert_delivery_status
    on platform.alert_delivery (status, created_at desc);

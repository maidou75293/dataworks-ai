-- 投递失败退避重试：attempts 记录已尝试次数，next_retry_at 之前不再重投。
alter table platform.alert_delivery
    add column if not exists attempts      integer not null default 0,
    add column if not exists next_retry_at timestamptz;

create index if not exists idx_alert_delivery_retry
    on platform.alert_delivery (status, next_retry_at);

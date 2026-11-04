-- P1-15：系统告警 resolved 后再次 firing 需要重新通知。
-- 事件每次重新打开时 firing_seq + 1；投递去重按 (event_id, channel_id, event_seq)，
-- 旧 firing 的 SENT 记录不再挡住新 firing。
alter table platform.alert_event
    add column if not exists firing_seq integer not null default 1;

alter table platform.alert_delivery
    add column if not exists event_seq integer not null default 1;

alter table platform.alert_delivery
    drop constraint if exists alert_delivery_event_id_channel_id_key;
create unique index if not exists uk_alert_delivery_event_channel_seq
    on platform.alert_delivery (event_id, channel_id, event_seq);

-- 通知渠道类型扩展：WEBHOOK / DINGTALK / FEISHU / EMAIL；
-- EMAIL 的 SMTP 参数放 config（host/port/username/password/from/to），密码单独加密。
alter table platform.notify_channel
    add column if not exists config jsonb;

-- 通知渠道签名密钥列太短：AES-GCM 密文比明文长（约 4/3 + 前缀），
-- 长密钥会在加密后写入时超长。数据服务 Token 在 V16 已经扩过，这里补齐。
alter table platform.notify_channel
    alter column secret type varchar(1024);

-- NULL_RATE / UNIQUE / RANGE 必须有字段；schema 层兜底，防止绕过 API 的写入
-- 产生「RANGE 对 null 列比较恒为 0 -> 规则误判通过」。
alter table platform.quality_rule
    drop constraint if exists ck_quality_column_required;
alter table platform.quality_rule
    add constraint ck_quality_column_required
    check (rule_type not in ('NULL_RATE', 'UNIQUE', 'RANGE') or column_name is not null);

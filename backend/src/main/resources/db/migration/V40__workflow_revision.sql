-- 乐观锁：每次保存 +1，客户端提交加载时的 revision，不匹配返回 409。
alter table platform.workflow
    add column if not exists revision bigint not null default 0;

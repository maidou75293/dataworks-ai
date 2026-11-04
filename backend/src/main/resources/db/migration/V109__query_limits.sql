-- D4 查询限额（2026-10-26）
-- 设计：共用一个 Doris 账号，按租户动态下发会话参数，不在 Doris 里建一堆用户。
-- 资源类（内存/超时/行数/并行度）交给 Doris 会话变量；并发与日配额由平台计数。

create table if not exists platform.query_limits (
    id                bigserial primary key,
    tenant_id         varchar(64) not null,
    user_id           bigint,                       -- null = 租户默认
    max_mem_mb        int     not null default 4096,
    timeout_s         int     not null default 60,
    max_rows          int     not null default 100000,
    max_parallel      int     not null default 4,
    max_concurrency   int     not null default 5,
    daily_query_limit int     not null default 0,   -- 0 = 不限
    enabled           boolean not null default true,
    updated_by        varchar(64),
    updated_at        timestamptz not null default now()
);

-- 用户级与租户级各一行；用 coalesce 索引兜住 null user_id 的唯一性
create unique index if not exists uq_query_limits_scope
    on platform.query_limits (tenant_id, coalesce(user_id, 0));

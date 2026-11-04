-- TC7-G4：按项目/租户用量计量——查询事件明细表。
-- 口径（docs/测试-TC7-G4.md §四评审确认表）：
--   * queryCount = 本表窗口内行数；resultRows = result_rows 合计；
--   * 采集点在 POST /api/query/doris 成功路径（400/403 不计量）；
--   * project_id 可空：不带项目的内置 Doris 直查只归租户，不冒充任何项目；
--   * scannedRows 不入第一版（无来源），存储/实例走既有表聚合，不在本表。
create table if not exists platform.usage_query_event (
    id           bigserial primary key,
    tenant_id    varchar(64) not null,
    project_id   bigint,
    username     varchar(64),
    result_rows  bigint      not null default 0,
    elapsed_ms   bigint      not null default 0,
    created_at   timestamptz not null default now()
);

-- 归集查询都是「租户/项目 + 时间窗」形态，两个复合索引按聚合入口建。
create index if not exists idx_usage_event_tenant_time
    on platform.usage_query_event (tenant_id, created_at);
create index if not exists idx_usage_event_project_time
    on platform.usage_query_event (project_id, created_at);

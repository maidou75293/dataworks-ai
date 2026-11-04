-- V120：表管理「附加库」登记表（REQ-DW-6）。
--
-- 背景：库归属此前只有 platform.project.dev_database / prod_database 两个登记名
-- （TenantDataScope.ownedDatabases 的唯一事实来源），表管理的「新建库」按钮因此
-- 只能「初始化」这两个环境库——新名字一律 404，等于每个项目锁死两个库。
--
-- 口径：
--   * 一个项目除了 DEV/PROD 环境库，还可以有若干「附加库」，命名规则固定
--     「业务英文名_后缀」（与 business_dev / business_prd 同族，项目内可辨认）；
--   * 本表记录归属 (tenant, project, database)；库名全局唯一（lower 判重），
--     跨租户撞名在建库接口即拒（非默认租户 404，不泄露存在性，与 BUG-TC3-1 口径一致）；
--   * 所有权读取：TenantDataScope.ownedDatabases() / ownerTenantsOf() 把本表并入，
--     库列表、建表、预览等数据面因此无需逐点改造。
--
-- 删除项目不级联删 Doris 库（与项目环境库同口径：保护数据）；on delete cascade 只清登记行。

create table if not exists platform.project_database (
    id            bigint generated always as identity primary key,
    tenant_id     varchar(64)  not null,
    project_id    bigint       not null references platform.project(id) on delete cascade,
    database_name varchar(128) not null,
    created_at    timestamptz  not null default now()
);

-- 库名全局唯一（大小写不敏感）：Doris 库名本身不区分大小写。
create unique index if not exists project_database_name_key
    on platform.project_database (lower(database_name));

create index if not exists idx_project_database_tenant
    on platform.project_database (tenant_id);

comment on table platform.project_database is
    'V120 / REQ-DW-6：项目附加库登记。命名=业务英文名_后缀；归属判定并入 TenantDataScope。';

-- 回滚：drop table if exists platform.project_database;
--       （Doris 侧已建的库不受影响，只是重新锁回「两个环境库」口径）

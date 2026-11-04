-- V116：数据源环境维度（REQ-TC4-1 第一条，TC-4 用例 DS-01 / DS-03）
--
-- 立案事实（TC-4 泳道实测）：platform.datasource 没有 env 列，DataSourceRequest 也没有
-- env 字段，响应键清单是 id/project_id/name/ds_type/jdbc_url/username/config/engine/
-- queryable/…。结果一条连生产库的连接和一条连开发库的连接，在平台上除了 jdbc_url 里的
-- 库名以外无法区分；「发布 → prd 库」在数据源这一层无处配置，也无法按环境列出/授权。
--
-- 项目层虽然有 dev_database / prod_database（D57），但那描述的是「项目的库」，
-- 不是「这条连接属于哪个环境」：同一个项目可以同时挂开发库连接与生产库连接用于核对。
--
-- 口径（与试跑 / 发布的 DEV / PROD 一致）：
--   DEV   开发环境连接（默认值，兼容全部历史数据）
--   PROD  生产环境连接
-- 其他取值：Controller 侧枚举校验直接 400（见 DataSourceController.normalizeEnv），
-- 这里是最后一道库级约束，防止绕过接口写库。
--
-- 幂等：add column if not exists + 约束按 conname 判重，可重复执行。

alter table platform.datasource
    add column if not exists env varchar(8) not null default 'DEV';

comment on column platform.datasource.env is
    '数据源所属环境（V116 / REQ-TC4-1）：DEV 或 PROD；默认 DEV 兼容历史数据';

do $$
begin
    if not exists (
        select 1 from pg_constraint
         where conrelid = 'platform.datasource'::regclass
           and conname = 'datasource_env_check'
    ) then
        alter table platform.datasource
            add constraint datasource_env_check check (env in ('DEV', 'PROD'));
    end if;
end $$;

-- 列表按环境过滤（GET /api/datasources?env=PROD）走 (tenant_id, env)
create index if not exists idx_datasource_tenant_env
    on platform.datasource (tenant_id, env);

-- 回滚：alter table platform.datasource drop column env;
--       （索引 idx_datasource_tenant_env 随列一起删除；约束 datasource_env_check 同理）

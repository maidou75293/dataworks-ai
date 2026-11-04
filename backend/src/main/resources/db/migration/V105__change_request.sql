-- V105：SQL 变更审核 v1（inbox/007 精简版，2026-10-26）
--
-- 只解决一件事：破坏性语句必须有人点「同意」。
--   * 只加一张表 platform.change_request（审批单本身）；
--   * 项目开关直接放 platform.project.sql_guard_enabled（不建策略表）；
--   * 权限点 change:submit（所有内置角色）/ change:approve（内置角色 SECURITY + ADMIN）。
--
-- 不做（007 第九节）：环境分级、SLA、双签、冷静期、EXPLAIN 预检、风险等级、策略表。
-- 回滚：drop table platform.change_request；alter table platform.project drop column
--       sql_guard_enabled；删除两个权限点与 SECURITY 角色即可（无历史数据依赖）。

create table if not exists platform.change_request (
    id            bigserial primary key,
    tenant_id     varchar(64) not null,
    project_id    bigint      not null,
    datasource_id bigint      not null,
    target_table  text,                 -- 表名（多表用逗号）
    sql_text      text        not null, -- 原始 SQL
    status        varchar(16) not null, -- PENDING / APPROVED / REJECTED / DONE / FAILED
    requester     varchar(64) not null,
    reviewer      varchar(64),
    review_comment text,
    backup_table  text,                 -- DROP/TRUNCATE 时执行前备份出的表名（多张用逗号）
    exec_result   text,
    created_at    timestamptz default now(),
    reviewed_at   timestamptz,
    executed_at   timestamptz
);

-- 列表默认口径：本租户 + 按状态倒序；审批用 id 直达但必须带 tenant_id
create index if not exists idx_change_request_tenant_status
    on platform.change_request (tenant_id, status, id desc);
create index if not exists idx_change_request_requester
    on platform.change_request (tenant_id, requester, id desc);

-- 项目级开关（默认开启；007 第四节：项目可关，管理员操作，落审计）
alter table platform.project
    add column if not exists sql_guard_enabled boolean not null default true;

-- 权限点目录（V55 的 permission_catalog；校验自定义角色时要用到）
insert into platform.permission_catalog (code, name, group_name, danger) values
  ('change:submit',  '提交 SQL 变更单', '数据安全', ''),
  ('change:approve', '审批 SQL 变更单', '数据安全', '高')
on conflict (code) do update set name = excluded.name,
    group_name = excluded.group_name, danger = excluded.danger;

-- 所有内置角色都能提交（007 第七节：change:submit 所有人）
insert into platform.role_permission (role_id, permission)
select r.id, 'change:submit'
from platform.role r
where r.builtin
on conflict (role_id, permission) do nothing;

-- 新内置角色 SECURITY（每个租户一份；后续新建租户由 RbacService.createTenant 复制）
insert into platform.role (tenant_id, code, name, builtin, status)
select t.id, 'SECURITY', '数据安全审批', true, 'ACTIVE'
from platform.tenant t
on conflict (tenant_id, code) do nothing;

-- SECURITY 的角色权限：只读类 + 提交 + 审批
insert into platform.role_permission (role_id, permission)
select r.id, m.permission
from platform.role r
cross join (values
  ('project:read'), ('workflow:read'), ('monitor:read'), ('sync:read'),
  ('datasource:read'), ('query:run'), ('catalog:read'), ('lineage:read'),
  ('quality:read'), ('service:read'), ('alert:read'), ('access:request'),
  ('agent:chat'), ('security:read'), ('audit:read'),
  ('change:submit'), ('change:approve')
) as m(permission)
where r.builtin and r.code = 'SECURITY'
on conflict (role_id, permission) do nothing;

-- ADMIN 也具备审批权（change:submit 已在上面的「所有内置角色」里）
insert into platform.role_permission (role_id, permission)
select r.id, 'change:approve'
from platform.role r
where r.builtin and r.code = 'ADMIN'
on conflict (role_id, permission) do nothing;

-- 权限模型第一期：建表 + 内置角色种子 + 存量数据搬迁。
-- 本期不改任何判定逻辑（仍是旧的 Permission/Role 枚举），只把「角色属于租户、
-- 角色可配权限点、用户可跨租户」的数据结构建起来并回填，便于第二期切换判定。
-- 回滚：drop 本文件新增的 6 张表即可，app_user.role 原样保留。

create table if not exists platform.tenant (
    id         bigserial primary key,
    code       varchar(64)  not null unique,
    name       varchar(128) not null,
    status     varchar(16)  not null default 'ACTIVE',
    created_at timestamptz  not null default now()
);

create table if not exists platform.tenant_member (
    tenant_id  bigint      not null references platform.tenant(id) on delete cascade,
    user_id    bigint      not null references platform.app_user(id) on delete cascade,
    status     varchar(16) not null default 'ACTIVE',
    is_default boolean     not null default false,
    joined_at  timestamptz not null default now(),
    primary key (tenant_id, user_id)
);

create table if not exists platform.role (
    id         bigserial primary key,
    tenant_id  bigint      not null references platform.tenant(id) on delete cascade,
    code       varchar(64) not null,
    name       varchar(128) not null,
    builtin    boolean     not null default false,
    status     varchar(16) not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    unique (tenant_id, code)
);

create table if not exists platform.role_permission (
    role_id    bigint      not null references platform.role(id) on delete cascade,
    permission varchar(64) not null,
    primary key (role_id, permission)
);

create table if not exists platform.member_role (
    tenant_id  bigint not null references platform.tenant(id) on delete cascade,
    user_id    bigint not null references platform.app_user(id) on delete cascade,
    role_id    bigint not null references platform.role(id) on delete cascade,
    primary key (tenant_id, user_id, role_id)
);

create table if not exists platform.permission_catalog (
    code        varchar(64) primary key,
    name        varchar(128) not null,
    group_name  varchar(64)  not null,
    danger      varchar(8)   not null default ''
);

-- 租户种子（存量默认租户 + 权限申请演示租户）
insert into platform.tenant (code, name, status) values
  ('default', '默认租户', 'ACTIVE'),
  ('tenant_b', '租户B', 'ACTIVE')
on conflict (code) do nothing;

-- 权限点目录
insert into platform.permission_catalog (code, name, group_name, danger) values
  ('project:read', '查看项目', '项目与组织', ''),
  ('project:manage', '建/改/删项目', '项目与组织', '高'),
  ('department:manage', '部门与组织架构', '项目与组织', '高'),
  ('workflow:read', '查看工作流/版本/DAG', '数据开发', ''),
  ('workflow:create', '新建工作流与目录', '数据开发', '中'),
  ('workflow:update', '修改工作流/目录/提交版本', '数据开发', '中'),
  ('workflow:delete', '删除工作流/目录', '数据开发', '高'),
  ('workflow:publish', '发布DEV/上线下线/回滚', '数据开发', '中'),
  ('workflow:approve', '审批PROD发布', '数据开发', '高'),
  ('monitor:read', '运行实例与监控', '调度与运行', ''),
  ('schedule:update', '修改调度配置', '调度与运行', '中'),
  ('ops:run', '手工触发/单实例重跑', '调度与运行', '中'),
  ('ops:backfill', '批量补数据', '调度与运行', '高'),
  ('ops:stop', '停止运行实例', '调度与运行', '中'),
  ('sync:read', '查看同步任务', '数据集成', ''),
  ('sync:write', '建/改同步任务', '数据集成', '中'),
  ('sync:delete', '删除同步任务', '数据集成', '高'),
  ('sync:run', '启停同步任务', '数据集成', '中'),
  ('datasource:read', '查看数据源', '数据源', ''),
  ('datasource:write', '建/改数据源/测试连接', '数据源', '中'),
  ('datasource:delete', '删除数据源', '数据源', '高'),
  ('query:run', '执行查询', '查询与资产', '中'),
  ('query:export', '导出查询结果', '查询与资产', '高'),
  ('catalog:read', '查看资产目录', '查询与资产', ''),
  ('catalog:manage', '维护资产与标签', '查询与资产', '中'),
  ('lineage:read', '查看血缘', '查询与资产', ''),
  ('data:classify', '给表/字段定级', '数据安全', '中'),
  ('mask:manage', '配置脱敏规则', '数据安全', '高'),
  ('quality:read', '查看质量规则与结果', '数据质量', ''),
  ('quality:write', '建/改质量规则', '数据质量', '中'),
  ('quality:run', '手动运行质量规则', '数据质量', '中'),
  ('service:read', '查看数据服务', '数据服务', ''),
  ('service:write', '建/改数据服务', '数据服务', '中'),
  ('service:publish', '发布/下线数据服务', '数据服务', '中'),
  ('service:token', '查看/轮换服务Token', '数据服务', '高'),
  ('service:delete', '删除数据服务', '数据服务', '高'),
  ('alert:read', '查看告警', '告警与通知', ''),
  ('alert:manage', '配置告警规则', '告警与通知', '中'),
  ('alert:ack', '确认/关闭告警', '告警与通知', '中'),
  ('notify:manage', '管理通知渠道', '告警与通知', '中'),
  ('access:request', '提交权限申请', '权限申请', ''),
  ('access:approve', '审批权限申请', '权限申请', '高'),
  ('access:revoke', '回收权限', '权限申请', '高'),
  ('user:read', '查看用户与成员', '租户与管理', ''),
  ('user:manage', '管理用户与成员', '租户与管理', '高'),
  ('role:read', '查看角色', '租户与管理', ''),
  ('role:manage', '配置角色与权限点', '租户与管理', '高'),
  ('tenant:read', '查看租户', '租户与管理', ''),
  ('tenant:manage', '租户设置', '租户与管理', '高'),
  ('audit:read', '查看审计日志', '租户与管理', ''),
  ('security:read', '查看安全中心', '租户与管理', ''),
  ('secret:manage', '管理密钥与凭证', '租户与管理', '高'),
  ('system:manage', '系统级配置', '租户与管理', '高'),
  ('agent:chat', '使用数据中台助手', '助手', ''),
  ('agent:memory:write', '助手写记忆/制度', '助手', '中'),
  ('agent:action:request', '助手代提交申请', '助手', '中')
on conflict (code) do update set name = excluded.name,
    group_name = excluded.group_name, danger = excluded.danger;

-- 内置角色：每个租户 8 个
insert into platform.role (tenant_id, code, name, builtin, status)
select t.id, r.code, r.name, true, 'ACTIVE'
from platform.tenant t
cross join (values
  ('VIEWER', '只读访客'),
  ('OPERATOR', '运维值班'),
  ('DEVELOPER', '数据开发'),
  ('APPROVER', '审批员'),
  ('STEWARD', '数据管家'),
  ('SECURITY_ADMIN', '数据安全管理员'),
  ('AUDITOR', '安全审计'),
  ('ADMIN', '平台管理员')
) as r(code, name)
on conflict (tenant_id, code) do nothing;

-- 角色 → 权限点
insert into platform.role_permission (role_id, permission)
select r.id, m.permission
from platform.role r
join (values
  ('VIEWER', 'project:read'),
  ('VIEWER', 'workflow:read'),
  ('VIEWER', 'monitor:read'),
  ('VIEWER', 'sync:read'),
  ('VIEWER', 'datasource:read'),
  ('VIEWER', 'query:run'),
  ('VIEWER', 'catalog:read'),
  ('VIEWER', 'lineage:read'),
  ('VIEWER', 'quality:read'),
  ('VIEWER', 'service:read'),
  ('VIEWER', 'alert:read'),
  ('VIEWER', 'access:request'),
  ('VIEWER', 'agent:chat'),
  ('OPERATOR', 'project:read'),
  ('OPERATOR', 'workflow:read'),
  ('OPERATOR', 'monitor:read'),
  ('OPERATOR', 'sync:read'),
  ('OPERATOR', 'datasource:read'),
  ('OPERATOR', 'query:run'),
  ('OPERATOR', 'catalog:read'),
  ('OPERATOR', 'lineage:read'),
  ('OPERATOR', 'quality:read'),
  ('OPERATOR', 'service:read'),
  ('OPERATOR', 'alert:read'),
  ('OPERATOR', 'access:request'),
  ('OPERATOR', 'agent:chat'),
  ('OPERATOR', 'ops:run'),
  ('OPERATOR', 'ops:backfill'),
  ('OPERATOR', 'ops:stop'),
  ('OPERATOR', 'sync:run'),
  ('OPERATOR', 'quality:run'),
  ('OPERATOR', 'alert:manage'),
  ('OPERATOR', 'alert:ack'),
  ('DEVELOPER', 'project:read'),
  ('DEVELOPER', 'workflow:read'),
  ('DEVELOPER', 'monitor:read'),
  ('DEVELOPER', 'sync:read'),
  ('DEVELOPER', 'datasource:read'),
  ('DEVELOPER', 'query:run'),
  ('DEVELOPER', 'catalog:read'),
  ('DEVELOPER', 'lineage:read'),
  ('DEVELOPER', 'quality:read'),
  ('DEVELOPER', 'service:read'),
  ('DEVELOPER', 'alert:read'),
  ('DEVELOPER', 'access:request'),
  ('DEVELOPER', 'agent:chat'),
  ('DEVELOPER', 'ops:run'),
  ('DEVELOPER', 'ops:backfill'),
  ('DEVELOPER', 'ops:stop'),
  ('DEVELOPER', 'sync:run'),
  ('DEVELOPER', 'quality:run'),
  ('DEVELOPER', 'alert:manage'),
  ('DEVELOPER', 'alert:ack'),
  ('DEVELOPER', 'workflow:create'),
  ('DEVELOPER', 'workflow:update'),
  ('DEVELOPER', 'workflow:delete'),
  ('DEVELOPER', 'workflow:publish'),
  ('DEVELOPER', 'schedule:update'),
  ('DEVELOPER', 'sync:write'),
  ('DEVELOPER', 'sync:delete'),
  ('DEVELOPER', 'datasource:write'),
  ('DEVELOPER', 'datasource:delete'),
  ('DEVELOPER', 'catalog:manage'),
  ('DEVELOPER', 'service:write'),
  ('DEVELOPER', 'service:publish'),
  ('DEVELOPER', 'service:token'),
  ('DEVELOPER', 'service:delete'),
  ('DEVELOPER', 'quality:write'),
  ('DEVELOPER', 'agent:memory:write'),
  ('DEVELOPER', 'agent:action:request'),
  ('APPROVER', 'project:read'),
  ('APPROVER', 'workflow:read'),
  ('APPROVER', 'monitor:read'),
  ('APPROVER', 'sync:read'),
  ('APPROVER', 'datasource:read'),
  ('APPROVER', 'query:run'),
  ('APPROVER', 'catalog:read'),
  ('APPROVER', 'lineage:read'),
  ('APPROVER', 'quality:read'),
  ('APPROVER', 'service:read'),
  ('APPROVER', 'alert:read'),
  ('APPROVER', 'access:request'),
  ('APPROVER', 'agent:chat'),
  ('APPROVER', 'access:approve'),
  ('APPROVER', 'access:revoke'),
  ('APPROVER', 'workflow:approve'),
  ('STEWARD', 'project:read'),
  ('STEWARD', 'workflow:read'),
  ('STEWARD', 'monitor:read'),
  ('STEWARD', 'sync:read'),
  ('STEWARD', 'datasource:read'),
  ('STEWARD', 'query:run'),
  ('STEWARD', 'catalog:read'),
  ('STEWARD', 'lineage:read'),
  ('STEWARD', 'quality:read'),
  ('STEWARD', 'service:read'),
  ('STEWARD', 'alert:read'),
  ('STEWARD', 'access:request'),
  ('STEWARD', 'agent:chat'),
  ('STEWARD', 'catalog:manage'),
  ('STEWARD', 'data:classify'),
  ('STEWARD', 'quality:write'),
  ('STEWARD', 'quality:run'),
  ('STEWARD', 'notify:manage'),
  ('SECURITY_ADMIN', 'project:read'),
  ('SECURITY_ADMIN', 'workflow:read'),
  ('SECURITY_ADMIN', 'monitor:read'),
  ('SECURITY_ADMIN', 'sync:read'),
  ('SECURITY_ADMIN', 'datasource:read'),
  ('SECURITY_ADMIN', 'query:run'),
  ('SECURITY_ADMIN', 'catalog:read'),
  ('SECURITY_ADMIN', 'lineage:read'),
  ('SECURITY_ADMIN', 'quality:read'),
  ('SECURITY_ADMIN', 'service:read'),
  ('SECURITY_ADMIN', 'alert:read'),
  ('SECURITY_ADMIN', 'access:request'),
  ('SECURITY_ADMIN', 'agent:chat'),
  ('SECURITY_ADMIN', 'data:classify'),
  ('SECURITY_ADMIN', 'mask:manage'),
  ('SECURITY_ADMIN', 'secret:manage'),
  ('SECURITY_ADMIN', 'access:approve'),
  ('SECURITY_ADMIN', 'access:revoke'),
  ('SECURITY_ADMIN', 'security:read'),
  ('SECURITY_ADMIN', 'audit:read'),
  ('AUDITOR', 'project:read'),
  ('AUDITOR', 'workflow:read'),
  ('AUDITOR', 'monitor:read'),
  ('AUDITOR', 'sync:read'),
  ('AUDITOR', 'datasource:read'),
  ('AUDITOR', 'query:run'),
  ('AUDITOR', 'catalog:read'),
  ('AUDITOR', 'lineage:read'),
  ('AUDITOR', 'quality:read'),
  ('AUDITOR', 'service:read'),
  ('AUDITOR', 'alert:read'),
  ('AUDITOR', 'access:request'),
  ('AUDITOR', 'agent:chat'),
  ('AUDITOR', 'audit:read'),
  ('AUDITOR', 'security:read'),
  ('ADMIN', 'project:read'),
  ('ADMIN', 'project:manage'),
  ('ADMIN', 'department:manage'),
  ('ADMIN', 'workflow:read'),
  ('ADMIN', 'workflow:create'),
  ('ADMIN', 'workflow:update'),
  ('ADMIN', 'workflow:delete'),
  ('ADMIN', 'workflow:publish'),
  ('ADMIN', 'workflow:approve'),
  ('ADMIN', 'monitor:read'),
  ('ADMIN', 'schedule:update'),
  ('ADMIN', 'ops:run'),
  ('ADMIN', 'ops:backfill'),
  ('ADMIN', 'ops:stop'),
  ('ADMIN', 'sync:read'),
  ('ADMIN', 'sync:write'),
  ('ADMIN', 'sync:delete'),
  ('ADMIN', 'sync:run'),
  ('ADMIN', 'datasource:read'),
  ('ADMIN', 'datasource:write'),
  ('ADMIN', 'datasource:delete'),
  ('ADMIN', 'query:run'),
  ('ADMIN', 'query:export'),
  ('ADMIN', 'catalog:read'),
  ('ADMIN', 'catalog:manage'),
  ('ADMIN', 'lineage:read'),
  ('ADMIN', 'data:classify'),
  ('ADMIN', 'mask:manage'),
  ('ADMIN', 'quality:read'),
  ('ADMIN', 'quality:write'),
  ('ADMIN', 'quality:run'),
  ('ADMIN', 'service:read'),
  ('ADMIN', 'service:write'),
  ('ADMIN', 'service:publish'),
  ('ADMIN', 'service:token'),
  ('ADMIN', 'service:delete'),
  ('ADMIN', 'alert:read'),
  ('ADMIN', 'alert:manage'),
  ('ADMIN', 'alert:ack'),
  ('ADMIN', 'notify:manage'),
  ('ADMIN', 'access:request'),
  ('ADMIN', 'access:approve'),
  ('ADMIN', 'access:revoke'),
  ('ADMIN', 'user:read'),
  ('ADMIN', 'user:manage'),
  ('ADMIN', 'role:read'),
  ('ADMIN', 'role:manage'),
  ('ADMIN', 'tenant:read'),
  ('ADMIN', 'tenant:manage'),
  ('ADMIN', 'audit:read'),
  ('ADMIN', 'security:read'),
  ('ADMIN', 'secret:manage'),
  ('ADMIN', 'system:manage'),
  ('ADMIN', 'agent:chat'),
  ('ADMIN', 'agent:memory:write'),
  ('ADMIN', 'agent:action:request')
) as m(role_code, permission) on m.role_code = r.code
where r.builtin
on conflict (role_id, permission) do nothing;

-- 存量用户 → 租户成员：按 app_user.tenant 落租户，空值归 default
insert into platform.tenant_member (tenant_id, user_id, status, is_default)
select t.id, u.id,
       case when u.status = 'ACTIVE' then 'ACTIVE' else 'DISABLED' end,
       (u.tenant = t.code)
from platform.app_user u
join platform.tenant t on t.code = coalesce(nullif(trim(u.tenant), ''), 'default')
on conflict (tenant_id, user_id) do nothing;

-- 内置管理员同时加入 tenant_b（演示跨租户）
insert into platform.tenant_member (tenant_id, user_id, status, is_default)
select t.id, u.id, 'ACTIVE', false
from platform.app_user u, platform.tenant t
where u.username = 'admin' and t.code = 'tenant_b'
on conflict (tenant_id, user_id) do nothing;

-- 旧角色列 → 同名内置角色（行为与现在完全一致）
insert into platform.member_role (tenant_id, user_id, role_id)
select tm.tenant_id, tm.user_id, r.id
from platform.app_user u
join platform.tenant_member tm on tm.user_id = u.id
join platform.role r on r.tenant_id = tm.tenant_id and r.code = u.role and r.builtin
on conflict (tenant_id, user_id, role_id) do nothing;

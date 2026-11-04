-- 助手权限模式开关（2026-10-24）
--
-- 背景：助手走 /api/internal/agent/*，内部凭证自带全权，读接口此前完全不看发起人权限，
-- 等于「谁有 agent:chat，谁就能借助手看全部数据」。但开发阶段又要用助手测功能，
-- 所以做成一个可运行时切换的开关：
--   SYSTEM   = 平台全权（测试用，默认）
--   DELEGATE = 跟随发起人权限点
-- 两种模式下「租户隔离」都生效——租户是数据边界，不是权限。
--
-- 回滚：delete from platform.agent_setting where setting_key='identity_mode';
--       切换按钮会随之消失，助手回到全权（与升级前一致）。

create table if not exists platform.agent_setting (
    setting_key   varchar(64) primary key,
    setting_value text        not null,
    updated_at    timestamptz not null default now(),
    updated_by    varchar(64)
);

insert into platform.agent_setting (setting_key, setting_value, updated_by)
values ('identity_mode', 'SYSTEM', 'migration')
on conflict (setting_key) do nothing;

-- 新权限点：改助手权限模式属于高风险配置，默认只给内置 ADMIN；
-- 其它角色要不要给（例如 SECURITY_ADMIN）由管理员在角色页自行勾选。
insert into platform.permission_catalog (code, name, group_name, danger)
values ('agent:config', '配置助手权限模式', '助手', '高')
on conflict (code) do update set name = excluded.name,
    group_name = excluded.group_name, danger = excluded.danger;

-- V55 写入的角色权限是静态快照，新增权限点要显式补到内置管理员
insert into platform.role_permission (role_id, permission)
select r.id, 'agent:config'
from platform.role r
where r.code = 'ADMIN' and r.builtin
on conflict (role_id, permission) do nothing;

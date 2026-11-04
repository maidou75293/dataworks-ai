-- REQ-UNIFY-1：账号统一·桥接网关权限点注册（号段 V150–159，避开 INT-5 预留的 V140–149）。
-- 语义：放行中台 /api/mos/** 代理通道（服务端会话代持 + 每请求审计，代码见
-- com.lab.dataworks.mosbridge）；无本权限点 = 403。入口控制归中台（merge-plan §2.4-2），
-- 页面内部 view/edit 仍由对方 12 模块权限决定，两套不互认。
insert into platform.permission_catalog (code, name, group_name, danger) values
  ('mos:access', '打开 ModelOneStation（标注/微调平台）', '模型微调', '中')
on conflict (code) do update set name = excluded.name,
    group_name = excluded.group_name, danger = excluded.danger;

-- 授予面与 V140 model:manage 同口径：内置 ADMIN + DEVELOPER（其余角色在角色页按需勾选；
-- 新租户由 RbacService.createTenant 从默认租户内置角色复制 role_permission，无需另行处理）。
insert into platform.role_permission (role_id, permission)
select r.id, 'mos:access'
from platform.role r
where r.builtin and r.code in ('DEVELOPER', 'ADMIN')
on conflict do nothing;

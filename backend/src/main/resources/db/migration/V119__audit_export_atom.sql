-- TC7-G3：审计日志导出（GET /api/security/audit/export，CSV/JSON 流式）。
-- 契约=docs/测试-TC7-G3.md 第四节第 5 条：新增权限点 security:export，授予面与 security:read 对齐
-- （守卫动态探测 catalog：出现即挂到被测主体；「出现却没人持有」R01–R04 保持红）。
insert into platform.permission_catalog (code, name, group_name, danger) values
  ('security:export', '导出审计日志', '数据安全', '高')
on conflict (code) do update set name = excluded.name,
    group_name = excluded.group_name, danger = excluded.danger;

-- 对齐授予面：凡当前持有 security:read 的角色（内置 + 自定义）一并获得 security:export。
-- 新租户由 RbacService.createTenant 从默认租户内置角色复制 role_permission，无需另行处理。
insert into platform.role_permission (role_id, permission)
select rp.role_id, 'security:export'
from platform.role_permission rp
where rp.permission = 'security:read'
on conflict (role_id, permission) do nothing;

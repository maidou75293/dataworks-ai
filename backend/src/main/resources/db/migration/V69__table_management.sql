-- 表管理：建库 / 建表 / 删表。读走 query:run，写单独一个高权限点。
insert into platform.permission_catalog (code, name, group_name, danger) values
  ('table:ddl', '建库/建表/删表', '表管理', '高')
on conflict (code) do nothing;

insert into platform.role_permission (role_id, permission)
select r.id, 'table:ddl'
from platform.role r
where r.builtin and r.code in ('DEVELOPER', 'ADMIN')
on conflict do nothing;

-- REQ-DW-12：内置角色从 9 个收敛为 5 个。
--   ADMIN          平台管理员（保留）
--   OWNER          项目Owner（新建；权限 = DEVELOPER ∪ 旧 APPROVER）
--   SECURITY_ADMIN 安全审核员（保留码、改名；权限并入旧 STEWARD/AUDITOR/SECURITY）
--   DEVELOPER      数据开发（保留；旧 OPERATOR 权限并入——V55 起即为 OPERATOR 超集）
--   VIEWER         只读访客（保留，不变）
-- 成员映射：OPERATOR→DEVELOPER，APPROVER→OWNER，STEWARD/AUDITOR/SECURITY→SECURITY_ADMIN。
-- 判据「无用户因迁移失去访问」：先把被删角色的权限点**并集复制**到目标角色，再重绑成员，
-- 所以重绑后每个用户的生效权限点只增不减。权限点用运行时数据复制，不硬编码清单
-- （V61/V68/V69/V82/V102/V105 陆续给部分内置角色加过权限点，硬编码必漏）。
-- 号段说明：V119 已被 wm1(TC7-G3)/wm3(TC7-G4) 分支占用，本迁移取 V130 避让。
-- 新建租户由 RbacService.createTenant 从 default 租户复制内置角色，迁移后自动只带 5 个。

-- 0) 迁移清单留档表：被删角色上的每一条成员绑定都记录 old→new，事后可查
create table if not exists platform.builtin_role_merge_log (
    id          bigint generated always as identity primary key,
    tenant_code varchar(64) not null,
    user_id     bigint      not null,
    old_role    varchar(64) not null,
    new_role    varchar(64) not null,
    created_at  timestamptz not null default now()
);

-- 1) 新内置角色 OWNER（每租户一份）
insert into platform.role (tenant_id, code, name, builtin, status)
select t.id, 'OWNER', '项目Owner', true, 'ACTIVE'
from platform.tenant t
on conflict (tenant_id, code) do nothing;

-- 2) 留档：被删角色上的现存成员绑定（必须在重绑/删除之前记录）
insert into platform.builtin_role_merge_log (tenant_code, user_id, old_role, new_role)
select tt.code, mr.user_id, s.code, d.code
from platform.member_role mr
join platform.role s on s.id = mr.role_id and s.builtin
join platform.role d on d.tenant_id = s.tenant_id
     and d.code = case s.code
                    when 'OPERATOR' then 'DEVELOPER'
                    when 'APPROVER' then 'OWNER'
                    when 'STEWARD'  then 'SECURITY_ADMIN'
                    when 'AUDITOR'  then 'SECURITY_ADMIN'
                    when 'SECURITY' then 'SECURITY_ADMIN'
                  end
join platform.tenant tt on tt.id = mr.tenant_id
where s.code in ('OPERATOR', 'APPROVER', 'STEWARD', 'AUDITOR', 'SECURITY');

-- 3) 权限点并集复制：src → dst（含 DEVELOPER→OWNER 的种子拷贝，DEVELOPER 本身不删）
insert into platform.role_permission (role_id, permission)
select d.id, rp.permission
from platform.role s
join platform.role_permission rp on rp.role_id = s.id
join platform.role d on d.tenant_id = s.tenant_id
     and d.code = case s.code
                    when 'OPERATOR'  then 'DEVELOPER'
                    when 'APPROVER'  then 'OWNER'
                    when 'DEVELOPER' then 'OWNER'
                    when 'STEWARD'   then 'SECURITY_ADMIN'
                    when 'AUDITOR'   then 'SECURITY_ADMIN'
                    when 'SECURITY'  then 'SECURITY_ADMIN'
                  end
where s.builtin
  and s.code in ('OPERATOR', 'APPROVER', 'STEWARD', 'AUDITOR', 'SECURITY', 'DEVELOPER')
on conflict (role_id, permission) do nothing;

-- 4) SECURITY_ADMIN 更名「安全审核员」（码不变，注解/前端色板引用不断）
update platform.role set name = '安全审核员'
where builtin and code = 'SECURITY_ADMIN';

-- 5) 成员重绑到目标角色（同租户已有目标角色的去重由 PK + on conflict 兜底）
insert into platform.member_role (tenant_id, user_id, role_id)
select distinct mr.tenant_id, mr.user_id, d.id
from platform.member_role mr
join platform.role s on s.id = mr.role_id and s.builtin
join platform.role d on d.tenant_id = s.tenant_id
     and d.code = case s.code
                    when 'OPERATOR' then 'DEVELOPER'
                    when 'APPROVER' then 'OWNER'
                    when 'STEWARD'  then 'SECURITY_ADMIN'
                    when 'AUDITOR'  then 'SECURITY_ADMIN'
                    when 'SECURITY' then 'SECURITY_ADMIN'
                  end
where s.code in ('OPERATOR', 'APPROVER', 'STEWARD', 'AUDITOR', 'SECURITY')
on conflict (tenant_id, user_id, role_id) do nothing;

-- 6) 摘除被删角色的旧绑定，并删除其授权与角色行（FK 均 on delete cascade，显式清理留痕）
delete from platform.member_role
where role_id in (select id from platform.role
                  where builtin and code in ('OPERATOR', 'APPROVER', 'STEWARD', 'AUDITOR', 'SECURITY'));
delete from platform.role_permission
where role_id in (select id from platform.role
                  where builtin and code in ('OPERATOR', 'APPROVER', 'STEWARD', 'AUDITOR', 'SECURITY'));
delete from platform.role
where builtin and code in ('OPERATOR', 'APPROVER', 'STEWARD', 'AUDITOR', 'SECURITY');

-- 7) app_user.role 历史列对齐：OPERATOR 内置已删，存量行统一落 DEVELOPER 桶
--   （CHECK 约束只允许 4 旧值；OWNER/SECURITY_ADMIN 等按 D18 口径本就落 VIEWER 桶，真实
--    权限以 member_role/权限点为准，见 Role.legacyOf）
update platform.app_user set role = 'DEVELOPER' where role = 'OPERATOR';

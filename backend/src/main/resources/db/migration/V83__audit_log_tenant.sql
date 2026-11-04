-- v448 第二轮审计 P1-1：审计日志之前没有租户维度，tenant_b 能读到 admin 的
-- 全部操作记录与 IP（跨租户行为画像），并为「按 id 直达」越权提供精确打击清单。
-- 这里补 tenant_id 并按用户默认租户回填；新写入由 SessionService.audit 负责。
alter table platform.audit_log add column if not exists tenant_id varchar(64);

update platform.audit_log a
   set tenant_id = coalesce(
       (select t.code
          from platform.app_user u
          join platform.tenant_member tm on tm.user_id = u.id and tm.status = 'ACTIVE'
          join platform.tenant t on t.id = tm.tenant_id
         where u.username = a.username
         order by tm.is_default desc, t.code
         limit 1),
       'default')
 where a.tenant_id is null;

create index if not exists idx_audit_log_tenant
    on platform.audit_log (tenant_id, id desc);

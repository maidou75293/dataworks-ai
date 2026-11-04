-- v448 第二轮审计 P2-2：安全中心统计按租户收口。
-- data_service_call 之前没有 tenant 维度，只能 join data_service → project；
-- 服务删除后 join 断掉，调用历史就不再计入（既有用例「删除服务后保留调用历史」）。
-- 这里补 tenant_id：写入时记录调用发生时的租户，删除服务也不影响历史统计。
alter table platform.data_service_call
    add column if not exists tenant_id varchar(64);

update platform.data_service_call c
   set tenant_id = coalesce(
       (select p.tenant_id
          from platform.data_service s
          join platform.project p on p.id = s.project_id
         where s.id = c.service_id),
       'default')
 where c.tenant_id is null;

create index if not exists idx_data_service_call_tenant
    on platform.data_service_call (tenant_id, created_at desc);

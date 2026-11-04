-- 权限模型第三期（第一步）：数据面加 tenant_id 并回填。本期不改查询条件，行为不变。
-- 第二步会按 tenant_id 过滤；回滚直接 drop 这些列即可。

alter table platform.project          add column if not exists tenant_id varchar(64) not null default 'default';
alter table platform.workflow         add column if not exists tenant_id varchar(64) not null default 'default';
alter table platform.workflow_version add column if not exists tenant_id varchar(64) not null default 'default';
alter table platform.wf_instance      add column if not exists tenant_id varchar(64) not null default 'default';
alter table platform.lineage_edge     add column if not exists tenant_id varchar(64) not null default 'default';
alter table platform.datasource       add column if not exists tenant_id varchar(64) not null default 'default';
alter table platform.sync_task        add column if not exists tenant_id varchar(64) not null default 'default';
alter table platform.data_service     add column if not exists tenant_id varchar(64) not null default 'default';
alter table platform.alert_rule       add column if not exists tenant_id varchar(64) not null default 'default';
alter table platform.quality_rule     add column if not exists tenant_id varchar(64) not null default 'default';

-- 回填：历史数据全部属于 default 租户；派生表跟随 project / workflow
update platform.workflow w
   set tenant_id = p.tenant_id
  from platform.project p
 where p.id = w.project_id and w.tenant_id <> p.tenant_id;

update platform.workflow_version v
   set tenant_id = w.tenant_id
  from platform.workflow w
 where w.id = v.workflow_id and v.tenant_id <> w.tenant_id;

update platform.wf_instance i
   set tenant_id = w.tenant_id
  from platform.workflow w
 where w.id = i.workflow_id and i.tenant_id <> w.tenant_id;

update platform.lineage_edge e
   set tenant_id = w.tenant_id
  from platform.workflow w
 where w.id = e.workflow_id and e.tenant_id <> w.tenant_id;

update platform.datasource d
   set tenant_id = p.tenant_id
  from platform.project p
 where p.id = d.project_id and d.tenant_id <> p.tenant_id;

create index if not exists idx_project_tenant on platform.project (tenant_id);
create index if not exists idx_workflow_tenant on platform.workflow (tenant_id, project_id);
create index if not exists idx_wf_instance_tenant on platform.wf_instance (tenant_id, workflow_id);
create index if not exists idx_lineage_tenant on platform.lineage_edge (tenant_id, env, workflow_id);
create index if not exists idx_datasource_tenant on platform.datasource (tenant_id, project_id);
create index if not exists idx_sync_task_tenant on platform.sync_task (tenant_id);
create index if not exists idx_data_service_tenant on platform.data_service (tenant_id);
create index if not exists idx_alert_rule_tenant on platform.alert_rule (tenant_id);
create index if not exists idx_quality_rule_tenant on platform.quality_rule (tenant_id);

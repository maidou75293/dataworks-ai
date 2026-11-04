-- 每个 (workflow, env) 只保留一个「当前生效」的 Prefect 部署。
--
-- 原实现把 deployment_id / flow_file 存在 workflow_version 行上：同一版本先发 DEV
-- 再发 PROD 会把 DEV 指针覆盖掉，导致 DEV 无法触发、旧部署的调度继续跑。
-- workflow_deployment 本来是发布历史表，这里把它升级为唯一事实来源。

alter table platform.workflow_deployment
    add column if not exists prefect_deployment_id varchar(256),
    add column if not exists prefect_flow_file varchar(512),
    add column if not exists prefect_flow_name varchar(256),
    add column if not exists active boolean not null default false;

-- 历史行尽量从版本行补齐部署指针（只有「版本行仍指向该环境」时才能补）
update platform.workflow_deployment d
set prefect_deployment_id = v.prefect_deployment_id,
    prefect_flow_file = v.prefect_flow_file,
    prefect_flow_name = v.prefect_flow_name
from platform.workflow_version v
where d.prefect_deployment_id is null
  and v.workflow_id = d.workflow_id
  and v.version_no = d.version_no
  and v.prefect_env = d.env
  and v.prefect_deployment_id is not null;

-- 有些环境只有版本行、没有对应的历史行，补一条
insert into platform.workflow_deployment
    (workflow_id, env, version_no, deployed_at, deployed_by,
     prefect_deployment_id, prefect_flow_file, prefect_flow_name, active)
select v.workflow_id, v.prefect_env, v.version_no,
       coalesce(v.published_at, v.created_at), v.published_by,
       v.prefect_deployment_id, v.prefect_flow_file, v.prefect_flow_name, true
from platform.workflow_version v
where v.status = 'PUBLISHED'
  and v.prefect_env in ('DEV', 'PROD')
  and v.prefect_deployment_id is not null
  and not exists (
      select 1 from platform.workflow_deployment d
      where d.workflow_id = v.workflow_id
        and d.env = v.prefect_env
        and d.prefect_deployment_id is not null
  );

-- 每个环境只把最新的、带部署指针的一条置为 active
with ranked as (
    select id,
           row_number() over (partition by workflow_id, env
                              order by deployed_at desc, id desc) as rn
    from platform.workflow_deployment
    where prefect_deployment_id is not null
)
update platform.workflow_deployment d
set active = (r.rn = 1)
from ranked r
where d.id = r.id;

create index if not exists idx_workflow_deployment_active
    on platform.workflow_deployment (workflow_id, env)
    where active;

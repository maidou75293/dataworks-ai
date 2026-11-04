-- 发布历史：同一条 workflow_version 可以被发布到 DEV 再发布到 PROD，
-- 只靠 workflow_version.prefect_env 会丢失前一个环境的发布记录。
-- 这里按「每次发布一条记录」保存，编辑器据此展示 DEV / PROD 当前版本。
create table if not exists platform.workflow_deployment (
    id bigserial primary key,
    workflow_id bigint not null references platform.workflow(id) on delete cascade,
    env varchar(16) not null,
    version_no int not null,
    deployed_at timestamptz not null default now(),
    deployed_by varchar(64)
);

create index if not exists idx_workflow_deployment_wf_env
    on platform.workflow_deployment (workflow_id, env, deployed_at desc);

-- 回填已有发布记录（能拿到的最近一次）
insert into platform.workflow_deployment (workflow_id, env, version_no, deployed_at, deployed_by)
select workflow_id, prefect_env, version_no,
       coalesce(published_at, created_at), published_by
from platform.workflow_version
where status = 'PUBLISHED' and prefect_env is not null;

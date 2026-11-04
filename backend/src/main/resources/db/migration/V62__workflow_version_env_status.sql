-- 版本状态按环境分开记录。
--
-- 原来 platform.workflow_version.status 是单列，跨 DEV/PROD 共用：
--   * PROD 已上线的版本再发一次 DEV，status/prefect_env 会变成 DEV，
--     线上明明是 PROD，版本列表却显示 DEV；
--   * 反过来，已上线版本重复提交 PROD 审批会把 status 改回 PENDING_APPROVAL。
-- 现在为每个环境单独记状态，status 只保留「最近一次部署」的兼容语义。
--
-- dev_status / prod_status 取值：
--   DRAFT 未发布, PENDING_APPROVAL 待审批(PROD), PUBLISHING 发布中,
--   PUBLISHED 当前线上, SUPERSEDED 已被新版本替换, OFFLINE 已下线

alter table platform.workflow_version add column if not exists dev_status varchar(32);
alter table platform.workflow_version add column if not exists prod_status varchar(32);
alter table platform.workflow_version add column if not exists prod_pending_at timestamptz;

-- 回填：某环境当前 active 的部署就是该环境的 PUBLISHED 版本
update platform.workflow_version v
   set dev_status = 'PUBLISHED'
  from platform.workflow_deployment d
 where d.workflow_id = v.workflow_id
   and d.version_no = v.version_no
   and d.env = 'DEV' and d.active and d.prefect_deployment_id is not null;

update platform.workflow_version v
   set prod_status = 'PUBLISHED'
  from platform.workflow_deployment d
 where d.workflow_id = v.workflow_id
   and d.version_no = v.version_no
   and d.env = 'PROD' and d.active and d.prefect_deployment_id is not null;

-- 之前发布到过某环境、但已被替换的版本
update platform.workflow_version v
   set dev_status = 'SUPERSEDED'
 where v.dev_status is null
   and exists (select 1 from platform.workflow_deployment d
                where d.workflow_id = v.workflow_id and d.version_no = v.version_no
                  and d.env = 'DEV');

update platform.workflow_version v
   set prod_status = 'SUPERSEDED'
 where v.prod_status is null
   and exists (select 1 from platform.workflow_deployment d
                where d.workflow_id = v.workflow_id and d.version_no = v.version_no
                  and d.env = 'PROD');

-- 单列 status 里的「待审批」只可能是 PROD 提交
update platform.workflow_version
   set prod_status = 'PENDING_APPROVAL', prod_pending_at = now()
 where status = 'PENDING_APPROVAL'
   and coalesce(prod_status, '') <> 'PUBLISHED';

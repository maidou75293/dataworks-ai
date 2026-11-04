-- V113 项目成员（D42：项目负责人/管理员在界面上不可配置）。
--
-- 数据模型原来只有单列 platform.project.owner_user（V50，默认 'admin'），
-- 结构上就支持不了「多个项目管理员」；创建项目时 resolveOwner 会写这一列，
-- 但更新路径从不写它，界面也没有入口 —— 能力只做了一半。
--
-- 口径：
--   * owner_user 保留为「负责人」主字段（很多地方的默认归属、审批人仍读它），不删；
--   * 本表记录「能管理这个项目的人」= OWNER（负责人，至少一个）+ ADMIN（项目管理员）；
--   * 判定「能否管理」统一走 ProjectController.canManageProject：owner_user ∪ 本表 OWNER/ADMIN。
--
-- 回填：把每个项目现有的 owner_user 落成一条 OWNER 成员，
-- 这样升级后老项目的负责人权限判定与新模型一致，不会出现「负责人突然管不了自己的项目」。

create table if not exists platform.project_member (
    project_id bigint    not null references platform.project(id) on delete cascade,
    username   varchar(64) not null,
    role       varchar(16) not null,
    created_at timestamptz not null default now(),
    primary key (project_id, username),
    constraint project_member_role_check check (role in ('OWNER', 'ADMIN'))
);

create index if not exists idx_project_member_user
    on platform.project_member (username);

comment on table platform.project_member is
    'D42：项目可管理成员。role OWNER=负责人（至少一个），ADMIN=项目管理员；owner_user 仍是主字段。';

insert into platform.project_member (project_id, username, role)
select id, owner_user, 'OWNER'
  from platform.project
 where owner_user is not null and owner_user <> ''
on conflict (project_id, username) do nothing;

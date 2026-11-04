-- 项目（仓库）负责人：创建时可选，默认创建者。
-- 负责人拥有项目库的全部权限，并审批其他人对该库的权限申请。
alter table platform.project
    add column if not exists owner_user varchar(64) not null default 'admin';

comment on column platform.project.owner_user is
    '项目负责人：拥有项目 Doris 库的全部读写权限，并审批其他人的库/表权限申请';

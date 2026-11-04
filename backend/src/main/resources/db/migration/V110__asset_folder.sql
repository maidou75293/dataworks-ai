-- V110：多模态资产目录 + 目录级授权（泳道 C，2026-10-26）
--
-- asset_folder        按租户隔离的目录树；path 物化（/a、/a/b），同租户同父目录下名称唯一。
-- asset.folder_id     资产归属目录；NULL = 虚拟根「我的上传」（存量资产全部为 NULL，行为不变）。
-- asset_folder_grant  目录级读写授权。语义（对齐主流文件盘 ACL 的最小可用版本）：
--   · 目录没有任何授权行 = 对本租户持有 asset:read / asset:write 的成员默认开放（向后兼容）；
--   · 一旦存在授权行，只有匹配的 USER（username）/ ROLE（本租户角色编码）主体能读/写该目录；
--   · 授权只作用于本目录，不向下继承；但浏览子目录要求其自身及全部祖先目录都「可读」，
--     所以关闭一个父目录会连带隐藏整棵子树；
--   · 目录创建者与平台/租户管理员（system:manage、tenant:manage、user:manage 权限点）始终保留
--     该目录的读/写/管理权，防止把自己锁死。
--
-- 幂等：建表/索引/加列都带 IF NOT EXISTS 或先 drop 再 add，重复执行安全。

create table if not exists platform.asset_folder (
    id          bigserial     primary key,
    tenant_id   varchar(64)   not null default 'default',
    parent_id   bigint        references platform.asset_folder (id) on delete restrict,
    name        varchar(64)   not null,
    path        varchar(1024) not null,
    created_by  varchar(64),
    created_at  timestamptz   not null default now()
);

comment on table platform.asset_folder is
    'V110：多模态资产目录树（租户隔离，path 物化）';
comment on column platform.asset_folder.path is
    '绝对路径，根目录的子目录为 /name；重命名/移动时整棵子树前缀替换';
comment on column platform.asset_folder.created_by is
    '创建者用户名；创建者对自己的目录始终有读/写/管理权（防锁死）';

-- PostgreSQL 的 UNIQUE 把 NULL 当成互不相等；根目录（parent_id is null）要用 coalesce 才能同层去重
create unique index if not exists uq_asset_folder_name
    on platform.asset_folder (tenant_id, coalesce(parent_id, 0), lower(name));
create unique index if not exists uq_asset_folder_path
    on platform.asset_folder (tenant_id, path);
create index if not exists ix_asset_folder_parent
    on platform.asset_folder (tenant_id, parent_id);

-- ---------------------------------------------------------------- 资产归属

alter table platform.asset add column if not exists folder_id bigint;

comment on column platform.asset.folder_id is
    'V110：所属目录；NULL=虚拟根「我的上传」，存量资产不动';

-- 服务层只允许删空目录；数据库层再兜一道 restrict，防止绕过服务层直接删库把资产悬空
alter table platform.asset drop constraint if exists asset_folder_fk;
alter table platform.asset add constraint asset_folder_fk
    foreign key (folder_id) references platform.asset_folder (id) on delete restrict;

-- 列表按目录过滤：租户 + 目录 + 时间倒序
create index if not exists ix_asset_tenant_folder
    on platform.asset (tenant_id, folder_id, created_at desc);

-- ---------------------------------------------------------------- 目录授权

create table if not exists platform.asset_folder_grant (
    id           bigserial   primary key,
    folder_id    bigint      not null
               references platform.asset_folder (id) on delete cascade,
    tenant_id    varchar(64) not null,
    subject_type varchar(8)  not null,
    subject_id   varchar(64) not null,
    can_read     boolean     not null default true,
    can_write    boolean     not null default false,
    created_by   varchar(64),
    created_at   timestamptz not null default now(),
    constraint ck_asset_folder_grant_subject_type
        check (subject_type in ('USER', 'ROLE')),
    constraint uq_asset_folder_grant_subject
        unique (folder_id, subject_type, subject_id)
);

comment on table platform.asset_folder_grant is
    'V110：目录级授权；无行=默认开放，有行=仅匹配主体可读写（创建者/管理员除外）';
comment on column platform.asset_folder_grant.subject_type is
    'USER（subject_id=app_user.username）或 ROLE（subject_id=本租户 platform.role.code）';
comment on column platform.asset_folder_grant.can_write is
    '写=可上传/移动入/移动到/删除该目录内资产；授权时 can_write 自动蕴含 can_read';

create index if not exists ix_asset_folder_grant_folder
    on platform.asset_folder_grant (folder_id);
-- 「某用户在某租户被哪些目录授权」：权限查看/列表过滤反查
create index if not exists ix_asset_folder_grant_subject
    on platform.asset_folder_grant (tenant_id, subject_type, subject_id);

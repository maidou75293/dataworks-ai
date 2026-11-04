-- V112：资产目录内容权接入「申请 → 审批 → 授权」链路（S11-2，泳道 wm1）。
--
-- 背景：S11 两层口径把「无内容授权」出口统一成 403 +「请提交权限申请」，但
-- access_request 的 resource_type 只接受 DATABASE/TABLE/COLUMN，目录内容权
-- 根本提交不了申请。本迁移把 ASSET_FOLDER 接进既有模型，不自造第二套状态机。
--
-- access_request：
--   · resource_type 允许 ASSET_FOLDER；
--   · folder_id 指向 platform.asset_folder（V110），是 ASSET_FOLDER 申请的真正资源键；
--     catalog 固定 'ASSET'、schema_name 存目录 path 快照（仅供列表/审批页展示，
--     超 128 截断；改名后不追更，以 folder_id 为准），table_name/column_name 恒空。
--   · 目录删除后 folder_id 置 NULL：历史申请仍可查，但除平台管理员外不能再批
--     （ownerTenant 解析为空 → canApprove 拒绝，与 managed_table 行被删的既有口径一致）。
--
-- asset_folder_grant：
--   · source 标记授权行来源：MANUAL=目录管理页 PUT 全量替换（AssetFolderService），
--     ACCESS=权限申请审批通过后物化（AccessController）。默认 MANUAL，存量行与
--     asset 侧现有读写逻辑（select 指定列、anyMatch 判定）零影响；
--   · 同一主体允许 MANUAL/ACCESS 各一行，判定时 OR（AssetFolderRules 是 anyMatch），
--     两个来源互不覆盖：回收/到期只重算 ACCESS 行，不踩目录管理员手工配的授权；
--   · 唯一键由 (folder_id, subject_type, subject_id) 扩为
--     (folder_id, subject_type, subject_id, source)；
--   · access_request_id 记录最近一次写该 ACCESS 行的申请（仅展示溯源，
--     回收/到期重算以 access_request 表为事实来源）。
--
-- 授权语义与 V110 保持一致：主体 USER/ROLE + can_read/can_write；
-- 授权只作用于本目录、不向下继承（本迁移与 access 侧代码都只写单目录行）。
--
-- 幂等：所有语句 drop-if-exists / if-not-exists，重复执行安全。

alter table platform.access_request
    add column if not exists folder_id bigint;

comment on column platform.access_request.folder_id is
    'V112：ASSET_FOLDER 申请的目标目录 id；目录删除后置 NULL（历史保留）';

alter table platform.access_request
    drop constraint if exists access_request_folder_fk;
alter table platform.access_request add constraint access_request_folder_fk
    foreign key (folder_id) references platform.asset_folder (id) on delete set null;

create index if not exists idx_access_request_folder
    on platform.access_request (folder_id) where folder_id is not null;

alter table platform.access_request
    drop constraint if exists access_request_type;
alter table platform.access_request
    add constraint access_request_type
        check (resource_type in ('DATABASE', 'TABLE', 'COLUMN', 'ASSET_FOLDER'));

-- ---------------------------------------------------------------- 目录授权来源标记

alter table platform.asset_folder_grant
    add column if not exists source varchar(8) not null default 'MANUAL';

alter table platform.asset_folder_grant
    add column if not exists access_request_id bigint;

comment on column platform.asset_folder_grant.source is
    'V112：MANUAL=目录管理页配置；ACCESS=权限申请审批物化（回收/到期只撤 ACCESS 行）';
comment on column platform.asset_folder_grant.access_request_id is
    'V112：最近一次写该 ACCESS 行的申请 id（仅展示溯源）';

alter table platform.asset_folder_grant
    drop constraint if exists ck_asset_folder_grant_source;
alter table platform.asset_folder_grant
    add constraint ck_asset_folder_grant_source check (source in ('MANUAL', 'ACCESS'));

-- 旧唯一键 (folder_id, subject_type, subject_id) 不允许两个来源各一行，换掉。
-- 原约束的内建索引随 drop constraint 一起删除，新唯一索引以 folder_id 打头，
-- 原先按 (folder_id,...) 的等值/前缀查询能力不丢。
alter table platform.asset_folder_grant
    drop constraint if exists uq_asset_folder_grant_subject;

create unique index if not exists uq_asset_folder_grant_subject_src
    on platform.asset_folder_grant (folder_id, subject_type, subject_id, source);

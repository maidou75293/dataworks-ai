-- V102：多模态资产（2026-10-26）
--
-- 图片 / PDF / 文本等非结构化文件存 MinIO（bucket media），平台只存元数据：
--   uri         s3://media/<tenant>/<yyyyMM>/<sha256>.<ext>
--   media_type  IMAGE / PDF / TEXT / AUDIO / VIDEO / OTHER（列表筛选用）
--   sha256      去重键：同租户同内容只保留一条 ACTIVE 记录
--   meta        JSONB，放宽高、页数、原始文件名等扩展信息
--   status      ACTIVE / DELETED（删除是软删，对象由运维按策略清理）
--
-- 幂等：建表/索引都带 IF NOT EXISTS，重复执行安全。

create table if not exists platform.asset (
    id          bigserial primary key,
    tenant_id   varchar(64)  not null,
    uri         text         not null,
    media_type  varchar(16)  not null default 'OTHER',
    mime        varchar(128),
    size_bytes  bigint       not null default 0,
    sha256      char(64),
    meta        jsonb        not null default '{}'::jsonb,
    status      varchar(16)  not null default 'ACTIVE',
    created_by  varchar(64),
    created_at  timestamptz  not null default now(),
    deleted_at  timestamptz
);

comment on table platform.asset is
    '多模态资产元数据（V102）：文件本体在 MinIO media bucket';
comment on column platform.asset.uri is
    's3://media/<tenant>/<yyyyMM>/<sha256>.<ext>，预签名 URL 由后端生成';
comment on column platform.asset.sha256 is
    '内容去重键；同租户同 sha256 只允许一条 ACTIVE';
comment on column platform.asset.status is
    'ACTIVE / DELETED；删除为软删，MinIO 对象由清理任务处理';

-- 去重只约束 ACTIVE：软删后同内容可以重新上传
create unique index if not exists ux_asset_tenant_sha256
    on platform.asset (tenant_id, sha256)
    where status = 'ACTIVE' and sha256 is not null;

-- 列表：租户 + 类型筛选 + 时间倒序（分页 keyset 也走这个索引）
create index if not exists ix_asset_tenant_type_created
    on platform.asset (tenant_id, media_type, created_at desc);

create index if not exists ix_asset_tenant_created
    on platform.asset (tenant_id, created_at desc);

-- ---------------------------------------------------------------- 权限点
-- 读：所有能查数据的角色；写：数据开发与管理员。
insert into platform.permission_catalog (code, name, group_name, danger) values
  ('asset:read',  '查看多模态资产',     '多模态资产', '低'),
  ('asset:write', '上传/删除多模态资产', '多模态资产', '中')
on conflict (code) do nothing;

insert into platform.role_permission (role_id, permission)
select r.id, p.code
from platform.role r, (values ('asset:read'), ('asset:write')) p(code)
where r.builtin and r.code in ('DEVELOPER', 'ADMIN')
on conflict do nothing;

insert into platform.role_permission (role_id, permission)
select r.id, 'asset:read'
from platform.role r
where r.builtin and r.code in ('VIEWER', 'OPERATOR')
on conflict do nothing;

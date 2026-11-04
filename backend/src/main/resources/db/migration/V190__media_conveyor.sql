-- REQ-FW-5 多模态数据同步骨干：中台 → mos 媒体搬运台账。
--
-- 台账口径 = docs/integration/media-ref.md §3.3：一条媒体一行，三段各记——
--   asset 段（asset_id / uri / sha256 / size_bytes，源=V102 元数据）
--   item 段（media_rel / ls_url，搬运成功后 mos 侧引用形态=库内相对路径）
--   task 段（item_ref / task_ref，推送后由 FW-1 推送器回填 mos item 与 LS task 指向）
-- 幂等键 unique (tenant_id, asset_id)；去重靠 sha256（同 src checksum 不重传）；
-- 断点续传靠 status/attempts：FAILED 可重试，OK 且 sha 未变跳过。

create table if not exists platform.media_transfer_ledger (
    id          bigserial primary key,
    tenant_id   varchar(64)  not null default 'default',
    asset_id    bigint       not null,
    asset_uri   text,                                    -- s3://media/<tenant>/<yyyyMM>/<sha256>.<ext>
    sha256      char(64),
    size_bytes  bigint,
    media_rel   text,                                    -- mos 媒体库相对路径（跨系统唯一引用形态）
    ls_url      text,                                    -- 搬运时 mos 回显（仅对账展示；消费时按 §2.2 现拼）
    item_ref    text,                                    -- mos item 指向 ds_id@version + source_ref（task 段前置）
    task_ref    text,                                    -- LS 任务指向 project/task id
    status      varchar(16)  not null default 'PENDING', -- PENDING | OK | FAILED
    attempts    integer      not null default 0,
    last_error  text,
    pushed      boolean      not null default false,     -- 先搬成功且已随任务推送（FW-1 钩子回填）
    created_by  varchar(64),
    created_at  timestamptz  not null default now(),
    updated_at  timestamptz  not null default now(),
    constraint uk_media_transfer_asset unique (tenant_id, asset_id)
);

create index if not exists ix_media_transfer_status
    on platform.media_transfer_ledger (tenant_id, status, updated_at desc);
create index if not exists ix_media_transfer_sha
    on platform.media_transfer_ledger (sha256);

-- 权限点：mos:media（媒体搬运发起 + 台账查询）。独立于 mos:access（UNIFY-1 代理通道）
-- 与 mos:flywheel（FW-2 产物回环）：本点是「把资产本体送到 mos 媒体库」的写操作。
insert into platform.permission_catalog (code, name, group_name, danger) values
  ('mos:media', '多模态媒体搬运', '模型微调', '中')
on conflict (code) do update set name = excluded.name,
    group_name = excluded.group_name, danger = excluded.danger;

-- 授予面与 V150/V170 同口径：内置 ADMIN + DEVELOPER。
insert into platform.role_permission (role_id, permission)
select r.id, 'mos:media'
from platform.role r
where r.builtin and r.code in ('DEVELOPER', 'ADMIN')
on conflict (role_id, permission) do nothing;

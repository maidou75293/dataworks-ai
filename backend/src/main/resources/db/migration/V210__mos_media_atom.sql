-- REQ-UNIFY-7：媒体库并入 Assets 页——权限点注册。
-- 号段：V150–159=UNIFY-1（mos:access）、V160=FW-1、V170=FW-2（mos_flywheel，main 已应用）、V180=FW-5（台账）。
-- 本文件原自选 V170 与 FW-2 撞号（分支基底早于其合入，自选时不可见），CONFLICT-WM5-1 主控评审确认改号 V210（V201+ 空闲）；SQL 内容未动。
-- 语义：放行 mos 媒体库管理面（/api/mos/api/media/**：列表/检索/上传/缩略图），
-- 判定在 MosProxyController 按路径追加（媒体库是 mos 侧单源桶 ms-swift-media 的全部原料，
-- 与「打开平台」的 mos:access 分开口径）。静态读穿 /media/** 不收紧——数据集详情的
-- 媒体预览（mosMediaUrl）早已依赖它，仍走 mos:access。
insert into platform.permission_catalog (code, name, group_name, danger) values
  ('mos:media', '媒体库浏览与上传（ModelOneStation）', '模型微调', '中')
on conflict (code) do update set name = excluded.name,
    group_name = excluded.group_name, danger = excluded.danger;

-- 授予面与 V150 同口径：内置 ADMIN + DEVELOPER（其余角色在角色页按需勾选；
-- 新租户由 RbacService.createTenant 复制内置角色 role_permission，无需另行处理）。
insert into platform.role_permission (role_id, permission)
select r.id, 'mos:media'
from platform.role r
where r.builtin and r.code in ('DEVELOPER', 'ADMIN')
on conflict do nothing;

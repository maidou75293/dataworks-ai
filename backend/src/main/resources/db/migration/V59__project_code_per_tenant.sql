-- 项目编码的唯一性从「全局」收敛到「租户内」。
--
-- 多租户之前 code 是全局 UNIQUE（V2），两个租户不能各有一个 project_01；
-- 跨租户并发建同名项目时，后到的那个收到 409，还顺带暴露了「另一个租户已有这个编码」。
-- 改成 (tenant_id, code) 唯一：租户内仍然禁止重复，租户之间互不影响。
alter table platform.project drop constraint if exists project_code_key;

create unique index if not exists uk_project_tenant_code
    on platform.project (tenant_id, code);

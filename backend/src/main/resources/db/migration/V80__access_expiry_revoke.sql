-- 权限申请：加有效期、回收状态。外部深度审计 F-1/F-2/F-3。
--   expires_at：批准时按 90 天写入，到期由定时任务置 EXPIRED，判定时也会过滤；
--   REVOKED：回收不再复用 CANCELLED（原来分不清是申请人撤销还是管理员回收）；
--   revoked_by/revoked_at：留痕。
alter table platform.access_request
    add column if not exists expires_at  timestamptz,
    add column if not exists revoked_by  varchar(128),
    add column if not exists revoked_at  timestamptz;

alter table platform.access_request
    drop constraint if exists access_request_status;

alter table platform.access_request
    add constraint access_request_status
        check (status in ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'REVOKED', 'EXPIRED'));

create index if not exists idx_access_request_expiry
    on platform.access_request (status, expires_at);

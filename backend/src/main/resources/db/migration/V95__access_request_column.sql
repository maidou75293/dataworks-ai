-- 列级权限申请（4b）。
--
--   column_name：resource_type=COLUMN 时记录申请/授权的列；
--   second_approver：L4 机密资源需要两位审批人，第一位记在 approver，第二位记在这里；
--   status=PENDING_SECOND：已通过一审、等待二审；
--   resource_type 增加 COLUMN。
--
-- 去重仍沿用 AccessController 的 advisory lock（锁键含 column_name），不建唯一索引。
alter table platform.access_request
    add column if not exists column_name varchar(128);

alter table platform.access_request
    add column if not exists second_approver varchar(64);

alter table platform.access_request
    drop constraint if exists access_request_type;
alter table platform.access_request
    add constraint access_request_type
        check (resource_type in ('DATABASE', 'TABLE', 'COLUMN'));

alter table platform.access_request
    drop constraint if exists access_request_status;
alter table platform.access_request
    add constraint access_request_status
        check (status in ('PENDING', 'PENDING_SECOND', 'APPROVED', 'REJECTED',
                          'CANCELLED', 'REVOKED', 'EXPIRED'));

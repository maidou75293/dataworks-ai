-- 存储提供方：hdfs / minio / oss（minio 与 oss 都走 S3 协议，用 provider 区分展示与签名）。
-- region：OSS 签名必需；MinIO 忽略，默认 us-east-1。
alter table platform.warehouse_connection
    add column if not exists provider varchar(16);
alter table platform.warehouse_connection
    add column if not exists region varchar(32);

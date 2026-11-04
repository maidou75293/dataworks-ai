-- MinIO/S3 连接需要 bucket（或前缀）。Doris/HDFS 连接留空。
alter table platform.warehouse_connection
    add column if not exists bucket_or_path varchar(256);

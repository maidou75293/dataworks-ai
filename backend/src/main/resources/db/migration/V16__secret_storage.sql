-- 敏感信息落盘加密：AES-GCM 密文比原 Token 长，放宽列宽。
-- 数据源 / 同步任务的密码列是 text，不需要改。
ALTER TABLE platform.data_service ALTER COLUMN api_token TYPE varchar(512);

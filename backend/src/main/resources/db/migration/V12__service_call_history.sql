-- 删除服务时保留调用历史（安全审计需要），只把服务引用置空
ALTER TABLE platform.data_service_call
    DROP CONSTRAINT IF EXISTS data_service_call_service_id_fkey;

ALTER TABLE platform.data_service_call
    ADD CONSTRAINT data_service_call_service_id_fkey
        FOREIGN KEY (service_id) REFERENCES platform.data_service(id) ON DELETE SET NULL;

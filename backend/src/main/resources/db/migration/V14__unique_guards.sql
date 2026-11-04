-- 唯一性不能只靠应用层「先查再插」：并发请求会同时通过检查。
-- 这里把三条业务唯一约束下沉到数据库，冲突时由全局异常处理返回 409。

-- 同一项目下数据源名称唯一
CREATE UNIQUE INDEX IF NOT EXISTS uk_datasource_project_name
    ON platform.datasource (project_id, name);

-- 同一项目下同步任务编码唯一
CREATE UNIQUE INDEX IF NOT EXISTS uk_sync_task_project_code
    ON platform.sync_task (project_id, code);

-- 数据服务的调用地址是 /api/ds/{code}，编码必须全局唯一
CREATE UNIQUE INDEX IF NOT EXISTS uk_data_service_code
    ON platform.data_service (code);

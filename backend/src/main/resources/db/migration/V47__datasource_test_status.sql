-- 数据源列表展示最近一次连接检测结果（不落密码、不存完整堆栈）。
alter table platform.datasource
    add column if not exists last_test_at timestamptz,
    add column if not exists last_test_ok boolean,
    add column if not exists last_test_message varchar(500);

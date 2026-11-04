-- 看板参数：图表 SQL 里用 :name 占位，查看时按看板参数赋值。
-- 存在开发态行上；发布时随快照一起冻结，改开发态参数不影响线上。
alter table platform.bi_dashboard add column if not exists params_json text;

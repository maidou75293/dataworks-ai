-- v457 MT-P1-1：租户切换。会话需要记住「当前生效租户」，否则切完下一个请求又回到
-- 默认租户（之前只有 X-Tenant-Id 请求头能切换，前端从不发这个头）。
-- 注意：路由表给 CC-A 预留的 V85 已被 v449 的 sync_task.ever_released 占用，
-- V86 被 Flink 状态 CHECK 占用，这里用下一个空闲号 V87。
alter table platform.app_session add column if not exists tenant varchar(64);

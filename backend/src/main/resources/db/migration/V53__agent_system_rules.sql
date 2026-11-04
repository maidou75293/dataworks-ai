-- 系统级规范：平台管理员维护，租户内所有项目与用户都生效（2026-10-23）
--
-- 之前的记忆只有 USER（个人）与 PROJECT（项目内共享）两级，
-- 平台自己的口径/命名/审批规则只能硬编码在 prompts.py 里，改一条要发版。
-- 增加 SYSTEM 级：owner 固定为 '*'，按租户读取，只有 ADMIN 能增删，
-- agent 注入时优先级最高（平台规范 > 项目口径/决策 > 事实 > 个人偏好）。

alter table platform.agent_memory
    drop constraint if exists agent_memory_scope;

alter table platform.agent_memory
    add constraint agent_memory_scope
    check (scope in ('USER', 'PROJECT', 'SYSTEM'));

create index if not exists idx_agent_memory_system
    on platform.agent_memory (tenant_id, scope, mem_type);

comment on column platform.agent_memory.scope is
    'USER 个人 / PROJECT 项目共享 / SYSTEM 平台规范（ADMIN 维护，owner=*）';

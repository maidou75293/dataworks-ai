-- v483：项目级安全策略覆盖（全局开关之后的第二层）。
--
-- 解析优先级：项目 > 租户 > 平台默认（见 DataGuardService.policy）。
-- 历史行语义不变：security_mode 默认 INHERIT（完全跟随平台），
-- default_column_level / column_control_required 默认 null（继承平台）。
--
--   security_mode           INHERIT / OFF / TABLE / COLUMN
--   default_column_level    新增列的默认数据等级，null = 平台默认 L2
--   column_control_required true 时强制字段级（覆盖 security_mode）
alter table platform.project
    add column if not exists security_mode varchar(16) not null default 'INHERIT';

alter table platform.project
    add column if not exists default_column_level varchar(8);

alter table platform.project
    add column if not exists column_control_required boolean;

-- 与代码里的枚举保持一致；约束放行历史默认值，不给存量行做任何改写。
alter table platform.project drop constraint if exists project_security_mode_check;
alter table platform.project add constraint project_security_mode_check
    check (security_mode in ('INHERIT', 'OFF', 'TABLE', 'COLUMN'));

alter table platform.project drop constraint if exists project_default_column_level_check;
alter table platform.project add constraint project_default_column_level_check
    check (default_column_level is null or default_column_level in ('L1', 'L2', 'L3', 'L4'));

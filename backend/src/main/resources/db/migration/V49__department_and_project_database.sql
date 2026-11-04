-- 部门管理 + 项目对应 Doris 库（2026-10-23）
--
-- 约定：
--   项目编码 = 部门编码_部门ID_业务英文名，例如 gx_01_gongxiang
--   Doris 库  = 业务英文名_dev / 业务英文名_prd，例如 gongxiang_dev、gongxiang_prd
--   表名前缀  = 部门默认前缀（可在部门上维护），例如 gx_

CREATE TABLE IF NOT EXISTS platform.department (
    id           bigserial PRIMARY KEY,
    code         varchar(16) NOT NULL UNIQUE,
    dept_id      varchar(16) NOT NULL UNIQUE,
    name         varchar(64) NOT NULL,
    table_prefix varchar(32) NOT NULL,
    owner_user   varchar(64),
    description  text,
    created_at   timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE platform.project
    ADD COLUMN IF NOT EXISTS department_id bigint REFERENCES platform.department(id) ON DELETE SET NULL;
ALTER TABLE platform.project
    ADD COLUMN IF NOT EXISTS business varchar(64);
ALTER TABLE platform.project
    ADD COLUMN IF NOT EXISTS dev_database varchar(64);
ALTER TABLE platform.project
    ADD COLUMN IF NOT EXISTS prod_database varchar(64);
ALTER TABLE platform.project
    ADD COLUMN IF NOT EXISTS table_prefix varchar(32);

-- 一个 Doris 库只能属于一个项目：避免两个业务名相同的项目抢同一个库。
CREATE UNIQUE INDEX IF NOT EXISTS uq_project_dev_database
    ON platform.project (dev_database) WHERE dev_database IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_project_prod_database
    ON platform.project (prod_database) WHERE prod_database IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_project_department ON platform.project (department_id);

-- 演示部门：gx_01_gongxiang / gongxiang_dev / gongxiang_prd / 表前缀 gx_
INSERT INTO platform.department (code, dept_id, name, table_prefix, owner_user, description)
VALUES
    ('gx', '01', '共享服务部', 'gx_', 'admin',
     '共享业务：项目编码 gx_01_业务，Doris 库 业务_dev / 业务_prd'),
    ('sj', '02', '数据平台部', 'sj_', 'admin',
     '平台自有数据：项目编码 sj_02_业务，Doris 库 业务_dev / 业务_prd')
ON CONFLICT (code) DO NOTHING;

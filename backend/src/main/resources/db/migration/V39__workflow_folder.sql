-- 数据开发：工作流按多级目录组织（传统项目结构），画布编辑保持不变。
ALTER TABLE platform.workflow ADD COLUMN IF NOT EXISTS folder varchar(240);

CREATE INDEX IF NOT EXISTS idx_workflow_folder
    ON platform.workflow (project_id, folder);

-- 没有工作流的空目录也要保留，所以单独存目录路径。
CREATE TABLE IF NOT EXISTS platform.workflow_folder (
    id         bigserial    PRIMARY KEY,
    project_id bigint       NOT NULL REFERENCES platform.project(id) ON DELETE CASCADE,
    path       varchar(240) NOT NULL,
    created_at timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (project_id, path)
);

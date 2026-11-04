-- RBAC：平台用户、角色、状态。角色矩阵在代码里（Role.java），库里只存角色编码。
CREATE TABLE IF NOT EXISTS platform.app_user (
    id            bigserial PRIMARY KEY,
    username      varchar(64)  NOT NULL UNIQUE,
    display_name  varchar(128),
    role          varchar(16)  NOT NULL DEFAULT 'VIEWER',
    status        varchar(16)  NOT NULL DEFAULT 'ACTIVE',
    source        varchar(16)  NOT NULL DEFAULT 'LOCAL',
    password_hash varchar(255),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    last_login_at timestamptz,
    CONSTRAINT ck_app_user_role CHECK (role IN ('ADMIN', 'DEVELOPER', 'OPERATOR', 'VIEWER')),
    CONSTRAINT ck_app_user_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE INDEX IF NOT EXISTS idx_app_user_role ON platform.app_user (role, status);

-- 内置管理员：密码来自 PLATFORM_ADMIN_PASSWORD，不落库；角色/状态不可在界面上修改。
INSERT INTO platform.app_user (username, display_name, role, status, source)
VALUES ('admin', '内置管理员', 'ADMIN', 'ACTIVE', 'LOCAL')
ON CONFLICT (username) DO UPDATE SET role = 'ADMIN', status = 'ACTIVE';

-- 升级前已经登录过的账号先按只读访客落库，管理员可在「用户与权限」里调整。
INSERT INTO platform.app_user (username, display_name, role, status, source)
SELECT DISTINCT s.username, s.username, 'VIEWER', 'ACTIVE', 'SSO'
FROM platform.app_session s
WHERE s.username IS NOT NULL AND s.username <> '' AND s.username <> 'admin'
ON CONFLICT (username) DO NOTHING;

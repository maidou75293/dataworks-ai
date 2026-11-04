CREATE TABLE IF NOT EXISTS platform.platform_meta (
    meta_key   varchar(64) PRIMARY KEY,
    meta_value text        NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO platform.platform_meta (meta_key, meta_value)
VALUES ('schema_version', '1')
ON CONFLICT (meta_key) DO UPDATE
    SET meta_value = EXCLUDED.meta_value,
        updated_at = now();

-- v3 policy-service schema (idempotent: CREATE TABLE IF NOT EXISTS + additive
-- ALTER for deployments that already ran the v2-era schema).
CREATE TABLE IF NOT EXISTS policy_instance (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(255) NOT NULL UNIQUE,
  dialect VARCHAR(64) NOT NULL,
  connection JSONB,
  connection_status VARCHAR(16) NOT NULL DEFAULT 'UNCONNECTED',
  config_version BIGINT NOT NULL DEFAULT 1,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS instance_table (
  id BIGSERIAL PRIMARY KEY,
  instance_id BIGINT NOT NULL REFERENCES policy_instance(id) ON DELETE CASCADE,
  catalog VARCHAR(255) NOT NULL,
  schema_name VARCHAR(255) NOT NULL,
  table_name VARCHAR(255) NOT NULL,
  position INT NOT NULL
);

CREATE TABLE IF NOT EXISTS instance_column (
  id BIGSERIAL PRIMARY KEY,
  table_id BIGINT NOT NULL REFERENCES instance_table(id) ON DELETE CASCADE,
  name VARCHAR(255) NOT NULL,
  type_declaration TEXT NOT NULL,
  position INT NOT NULL
);

CREATE TABLE IF NOT EXISTS policy (
  id BIGSERIAL PRIMARY KEY,
  instance_id BIGINT NOT NULL REFERENCES policy_instance(id) ON DELETE CASCADE,
  name VARCHAR(255) NOT NULL,
  access_type VARCHAR(16) NOT NULL DEFAULT 'SELECT',
  policy_type VARCHAR(32) NOT NULL,
  is_enabled BOOLEAN NOT NULL,
  udf VARCHAR(255),
  arguments JSONB,
  filter_expr TEXT,
  resource JSONB NOT NULL,
  subjects JSONB,
  priority INT NOT NULL DEFAULT 0,
  current_version INT NOT NULL DEFAULT 1,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (instance_id, name)
);

-- Immutable per-policy version history, keyed by (instance, policy name) so it
-- survives policy deletion (spec: 删除保留历史) and a recreate continues the
-- version sequence (同名重建续用版本序列). content holds a full PolicyEntity
-- snapshot; source_version records the version a ROLLBACK restored from.
CREATE TABLE IF NOT EXISTS policy_version (
  id BIGSERIAL PRIMARY KEY,
  instance_id BIGINT NOT NULL REFERENCES policy_instance(id) ON DELETE CASCADE,
  policy_name VARCHAR(255) NOT NULL,
  version INT NOT NULL,
  change_type VARCHAR(16) NOT NULL,
  content JSONB NOT NULL,
  source_version INT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (instance_id, policy_name, version)
);

CREATE TABLE IF NOT EXISTS instance_udf (
  id BIGSERIAL PRIMARY KEY,
  instance_id BIGINT NOT NULL REFERENCES policy_instance(id) ON DELETE CASCADE,
  name VARCHAR(255) NOT NULL,
  param_types TEXT NOT NULL,
  return_type VARCHAR(255) NOT NULL,
  position INT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (instance_id, name, param_types)
);

-- Graceful upgrade for deployments on the pre-v3 schema.
ALTER TABLE policy_instance ADD COLUMN IF NOT EXISTS connection JSONB;
ALTER TABLE policy_instance ADD COLUMN IF NOT EXISTS connection_status VARCHAR(16) NOT NULL DEFAULT 'UNCONNECTED';
ALTER TABLE policy ADD COLUMN IF NOT EXISTS access_type VARCHAR(16) NOT NULL DEFAULT 'SELECT';
ALTER TABLE policy ADD COLUMN IF NOT EXISTS current_version INT NOT NULL DEFAULT 1;
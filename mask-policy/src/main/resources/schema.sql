CREATE TABLE IF NOT EXISTS policy_instance (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(255) NOT NULL UNIQUE,
  dialect VARCHAR(64) NOT NULL,
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
  policy_type VARCHAR(32) NOT NULL,
  is_enabled BOOLEAN NOT NULL,
  udf VARCHAR(255),
  arguments JSONB,
  filter_expr TEXT,
  resource JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (instance_id, name)
);

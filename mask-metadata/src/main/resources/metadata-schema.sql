CREATE TABLE IF NOT EXISTS meta_instance (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(255) NOT NULL UNIQUE,
  dialect VARCHAR(64) NOT NULL,
  host VARCHAR(255),
  port INT,
  database VARCHAR(255),
  db_user VARCHAR(255),
  password_ref VARCHAR(255),
  sslmode VARCHAR(32),
  connect_timeout_seconds INT,
  schemas JSONB,
  include_views BOOLEAN NOT NULL DEFAULT FALSE,
  metadata_version BIGINT NOT NULL DEFAULT 1,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS meta_table (
  id BIGSERIAL PRIMARY KEY,
  instance_id BIGINT NOT NULL REFERENCES meta_instance(id) ON DELETE CASCADE,
  catalog VARCHAR(255) NOT NULL,
  schema_name VARCHAR(255) NOT NULL,
  table_name VARCHAR(255) NOT NULL,
  position INT NOT NULL,
  UNIQUE (instance_id, catalog, schema_name, table_name)
);

CREATE TABLE IF NOT EXISTS meta_column (
  id BIGSERIAL PRIMARY KEY,
  table_id BIGINT NOT NULL REFERENCES meta_table(id) ON DELETE CASCADE,
  name VARCHAR(255) NOT NULL,
  type_declaration TEXT NOT NULL,
  position INT NOT NULL
);

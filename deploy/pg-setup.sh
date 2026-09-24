#!/usr/bin/env bash
# PG 初始化：密码 + 三库 + crm 种子数据与脱敏 UDF
set -e
S=/opt/sqlmask
sudo -u postgres psql -qc "ALTER USER postgres PASSWORD 'PgTest2026'"
for db in crm mask_policy mask_metadata; do
  sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='$db'" | grep -q 1 || sudo -u postgres createdb "$db"
done
sudo -u postgres psql -d crm -v ON_ERROR_STOP=1 -q < $S/01_udf.sql
sudo -u postgres psql -d crm -v ON_ERROR_STOP=1 -q < $S/02_crm.sql
echo SEEDED
sudo -u postgres psql -d crm -tAc "SELECT count(*) FROM customer; SELECT mask_phone(phone,3,4) FROM customer WHERE id=1; SELECT count(*) FROM orders WHERE region='north'"
sudo -u postgres psql -tAc "SELECT datname FROM pg_database ORDER BY 1"

#!/usr/bin/env bash
# Restores the latest (or given) encrypted backup into a SCRATCH database and runs sanity checks.
# Never targets the production database. Env: BACKUP_BUCKET AGE_IDENTITY_FILE DB_HOST DB_USER PGPASSWORD
set -euo pipefail
key="${1:-$(aws s3 ls "s3://${BACKUP_BUCKET}/postgres/" ${AWS_ENDPOINT_URL:+--endpoint-url "$AWS_ENDPOINT_URL"} | sort | tail -1 | awk '{print $4}')}"
scratch="vendocas_restore_$(date +%s)"
aws s3 cp "s3://${BACKUP_BUCKET}/postgres/${key}" /tmp/restore.age ${AWS_ENDPOINT_URL:+--endpoint-url "$AWS_ENDPOINT_URL"}
age -d -i "${AGE_IDENTITY_FILE}" /tmp/restore.age > /tmp/restore.dump
psql -h "${DB_HOST}" -U "${DB_USER}" -d postgres -c "CREATE DATABASE ${scratch}"
pg_restore -h "${DB_HOST}" -U "${DB_USER}" -d "${scratch}" --no-owner /tmp/restore.dump
psql -h "${DB_HOST}" -U "${DB_USER}" -d "${scratch}" -v ON_ERROR_STOP=1 <<'SQL'
SELECT max(version) AS flyway_version FROM flyway_schema_history WHERE success;
SELECT count(*) AS organizations FROM organizations;
SELECT count(*) AS pending_instances FROM automation_instances WHERE state IN ('PENDING','WAITING','READY');
SQL
echo "Restore OK into ${scratch}. Drop it when done: DROP DATABASE ${scratch};"
rm -f /tmp/restore.age /tmp/restore.dump

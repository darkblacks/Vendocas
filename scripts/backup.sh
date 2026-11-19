#!/usr/bin/env bash
# Encrypted logical backup of PostgreSQL to S3-compatible storage.
# Requires: pg_dump, age (https://age-encryption.org), aws CLI configured for the backup bucket.
# Env: DB_HOST DB_USER PGPASSWORD DB_NAME BACKUP_BUCKET AGE_RECIPIENT [AWS_ENDPOINT_URL]
set -euo pipefail
ts=$(date -u +%Y%m%dT%H%M%SZ)
file="vendocas-${ts}.dump.age"
pg_dump -h "${DB_HOST:-localhost}" -U "${DB_USER:-vendocas}" -d "${DB_NAME:-vendocas}" -Fc --no-owner \
  | age -r "${AGE_RECIPIENT}" > "/tmp/${file}"
aws s3 cp "/tmp/${file}" "s3://${BACKUP_BUCKET}/postgres/${file}" ${AWS_ENDPOINT_URL:+--endpoint-url "$AWS_ENDPOINT_URL"}
rm -f "/tmp/${file}"
echo "uploaded ${file}"

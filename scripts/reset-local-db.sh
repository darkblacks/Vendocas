#!/usr/bin/env bash
# Drops and recreates the local development databases (NEVER run against production).
set -euo pipefail
PSQL="${PSQL:-psql -h localhost -U vendocas -d postgres}"
for db in vendocas vendocas_test; do
  $PSQL -c "DROP DATABASE IF EXISTS $db WITH (FORCE);" -c "CREATE DATABASE $db OWNER vendocas;"
done

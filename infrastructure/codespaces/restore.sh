#!/usr/bin/env bash
# Restores a backup INTO A NEW DATABASE (vendocas_restore_<ts>) — the live database is never dropped or overwritten.
# To switch the API to it, set DB_URL accordingly and restart (see docs/codespaces-setup.md).
#   bash infrastructure/codespaces/restore.sh /workspaces/.vendocas/backups/vendocas-XXXX.dump
set -euo pipefail
# shellcheck source=infrastructure/codespaces/lib.sh
source "$(dirname "$0")/lib.sh"
STEP=restore
src="${1:?uso: restore.sh <arquivo.dump>}"
[ -f "$src" ] || fail "arquivo não encontrado: $src"
target="vendocas_restore_$(date -u +%Y%m%d%H%M%S)"
export PGPASSWORD="${DB_PASSWORD:-vendocas}"
createdb -h postgres -U "${DB_USER:-vendocas}" "$target"
pg_restore -h postgres -U "${DB_USER:-vendocas}" -d "$target" --no-owner "$src"
log "restaurado em banco novo: $target (o banco 'vendocas' não foi alterado)"

#!/usr/bin/env bash
# Logical backup of the homologation database to /workspaces/.vendocas/backups (persists across stop/start and container
# rebuilds, NOT across codespace deletion — download important backups: `gh codespace cp`). Keeps the last 14.
set -euo pipefail
# shellcheck source=infrastructure/codespaces/lib.sh
source "$(dirname "$0")/lib.sh"
STEP=backup
file="$BACKUP_DIR/vendocas-$(date -u +%Y%m%dT%H%M%SZ).dump"
PGPASSWORD="${DB_PASSWORD:-vendocas}" pg_dump -h postgres -U "${DB_USER:-vendocas}" -d vendocas -Fc --no-owner -f "$file"
chmod 600 "$file"
log "backup criado: $file ($(du -h "$file" | cut -f1))"
# shellcheck disable=SC2012
ls -1t "$BACKUP_DIR"/vendocas-*.dump | tail -n +15 | xargs -r rm -f

#!/usr/bin/env bash
# Shared helpers for the Codespace scripts. Sourced, not executed.
# shellcheck disable=SC2034
STATE_DIR="${VENDOCAS_STATE_DIR:-/workspaces/.vendocas}"
LOG_DIR="$STATE_DIR/logs"
RUN_DIR="$STATE_DIR/run"
BACKUP_DIR="$STATE_DIR/backups"
SECRETS_FILE="$STATE_DIR/secrets.env"
STATUS_FILE="$STATE_DIR/status.json"
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
mkdir -p "$LOG_DIR" "$RUN_DIR" "$BACKUP_DIR"
chmod 700 "$STATE_DIR"

log() { printf '%s [%s] %s\n' "$(date -u +%FT%TZ)" "${STEP:-codespace}" "$*" | tee -a "$LOG_DIR/startup.log" >&2; }

status() { # status <phase> <ok|error|running> <message>
  local commit; commit="$(git -C "$REPO_DIR" rev-parse --short HEAD 2>/dev/null || echo unknown)"
  printf '{"phase":"%s","state":"%s","message":"%s","commit":"%s","updatedAt":"%s"}\n' \
    "$1" "$2" "${3//\"/\'}" "$commit" "$(date -u +%FT%TZ)" > "$STATUS_FILE.tmp" && mv "$STATUS_FILE.tmp" "$STATUS_FILE"
}

fail() { log "ERRO: $*"; status "${STEP:-unknown}" error "$*"; exit 1; }

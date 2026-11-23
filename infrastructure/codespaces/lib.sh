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

# Maven flags for the installed JDK. The API targets Java 25; with an older JDK (21+) it is compiled at that level,
# exactly like scripts/dev.sh does for local development (a Codespace created from another image may have Java 21).
mvn_java_flags() {
  local jv
  jv="$(java -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p' | head -1)"
  if [ -z "$jv" ]; then echo ""; return; fi
  if [ "$jv" -lt 21 ]; then fail "Java $jv encontrado; é necessário Java 21 ou mais novo (ideal: 25 — Codespaces: Rebuild Container)"; fi
  if [ "$jv" -lt 25 ]; then log "aviso: Java $jv detectado (o projeto usa 25); compilando com -Djava.version=$jv"; echo "-Djava.version=$jv"; fi
}

# The Maven cache is a Docker named volume (.devcontainer/docker-compose.yml): Docker creates its mount point owned by
# root, so the codespace user cannot write to it ("Could not create local repository"). Fix the ownership once.
ensure_m2_writable() {
  local m2="${HOME}/.m2"
  mkdir -p "$m2" 2>/dev/null || true
  if [ ! -w "$m2" ]; then
    if command -v sudo >/dev/null && sudo -n true 2>/dev/null; then
      sudo chown -R "$(id -u):$(id -g)" "$m2" && log "permissões de $m2 corrigidas"
    else
      fail "$m2 não é gravável e não há sudo: rode 'sudo chown -R \$(id -u):\$(id -g) $m2'"
    fi
  fi
}

# Runs a Maven build into a log file and, on failure, shows the end of the log right away.
build_api() {
  local logfile="$1" flags
  ensure_m2_writable
  flags="$(mvn_java_flags)"
  # shellcheck disable=SC2086
  if ! (cd "$REPO_DIR/apps/api" && mvn -B -q $flags -DskipTests package) >>"$logfile" 2>&1; then
    echo "------ últimas linhas de $logfile ------" >&2
    tail -n 40 "$logfile" >&2
    echo "----------------------------------------" >&2
    return 1
  fi
}

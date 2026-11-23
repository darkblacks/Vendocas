#!/usr/bin/env bash
# postStartCommand — runs every time the codespace starts (including after a stop).
#   1. validate environment  2. identify code version  3. update main safely (fast-forward only, clean tree only)
#   4. dependencies/build if changed  5. infrastructure up  6. API (Flyway applies pending migrations; incompatible ones
#   abort startup)  7. workers (inside the API)  8. health checks  9. status file for the Control Center.
# Never: reset/clean/stash, force operations, database drops or volume removal.
set -euo pipefail
# shellcheck source=infrastructure/codespaces/lib.sh
source "$(dirname "$0")/lib.sh"
cd "$REPO_DIR"

STEP=validate; status validate running "validando ambiente"
for t in java mvn node pnpm git pg_isready redis-cli curl; do command -v "$t" >/dev/null || fail "ferramenta ausente: $t"; done
[ -f "$SECRETS_FILE" ] || fail "segredos de homologação ausentes; rode infrastructure/codespaces/setup.sh"
PRESET_STATUS_TOKEN="${CONTROL_STATUS_TOKEN:-}" # a Codespaces secret, when defined, wins over the generated one
set -a; # shellcheck disable=SC1090
source "$SECRETS_FILE"; set +a
[ -n "$PRESET_STATUS_TOKEN" ] && CONTROL_STATUS_TOKEN="$PRESET_STATUS_TOKEN"
[ "${#CONTROL_STATUS_TOKEN}" -ge 32 ] || fail "CONTROL_STATUS_TOKEN precisa ter ao menos 32 caracteres"
# Codespaces created before the admin token existed: add it once (lets the Control Center change platform settings).
if [ -z "${CONTROL_ADMIN_TOKEN:-}" ]; then
  CONTROL_ADMIN_TOKEN="$(openssl rand -base64 36 | tr '+/' '-_' | tr -d '=')"
  (umask 077; echo "CONTROL_ADMIN_TOKEN=$CONTROL_ADMIN_TOKEN" >> "$SECRETS_FILE")
  export CONTROL_ADMIN_TOKEN
  log "CONTROL_ADMIN_TOKEN gerado (veja infrastructure/codespaces/show-admin-token.sh)"
fi

STEP=version
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
log "branch=$BRANCH commit=$(git rev-parse --short HEAD)"

STEP=update; status update running "atualizando cópia local"
if [ "${VENDOCAS_AUTO_UPDATE:-true}" != "true" ]; then
  log "atualização automática desativada (VENDOCAS_AUTO_UPDATE=false)"
elif [ "$BRANCH" != "main" ]; then
  log "branch $BRANCH ≠ main: não atualizo automaticamente"
elif [ -n "$(git status --porcelain)" ]; then
  log "há alterações locais não commitadas: atualização ignorada para preservá-las"
elif ! git fetch --quiet origin main; then
  log "git fetch falhou (rede?): seguindo com a versão local"
elif [ -n "$(git rev-list origin/main..HEAD)" ]; then
  log "há commits locais não enviados: atualização ignorada (resolva manualmente)"
elif ! git merge --ff-only --quiet origin/main; then
  fail "não foi possível avançar main por fast-forward; nenhuma alteração foi feita — resolva manualmente"
fi
COMMIT="$(git rev-parse HEAD)"
export VENDOCAS_GIT_COMMIT="$COMMIT"
log "versão em execução: ${COMMIT:0:7}"

STEP=deps; status deps running "verificando dependências"
LOCK_HASH="$(sha256sum pnpm-lock.yaml | cut -c1-16)"
if [ "$(cat "$RUN_DIR/pnpm.lock.hash" 2>/dev/null)" != "$LOCK_HASH" ]; then
  pnpm install --frozen-lockfile >>"$LOG_DIR/startup.log" 2>&1 || fail "pnpm install falhou"
  echo "$LOCK_HASH" > "$RUN_DIR/pnpm.lock.hash"
fi
JAR="$(ls apps/api/target/vendocas-api-*.jar 2>/dev/null | head -1 || true)"
if [ -z "$JAR" ] || [ "$(cat "$RUN_DIR/api.build.commit" 2>/dev/null)" != "$COMMIT" ]; then
  log "compilando API para ${COMMIT:0:7}"
  : > "$LOG_DIR/build.log"
  build_api "$LOG_DIR/build.log" || fail "build da API falhou (erro acima; log completo em $LOG_DIR/build.log)"
  echo "$COMMIT" > "$RUN_DIR/api.build.commit"
  JAR="$(ls apps/api/target/vendocas-api-*.jar | head -1)"
fi

STEP=infra; status infra running "aguardando Postgres e Redis"
for _ in $(seq 1 60); do pg_isready -q -h postgres -U vendocas && break; sleep 2; done
pg_isready -q -h postgres -U vendocas || fail "Postgres indisponível"
redis-cli -h redis ping | grep -q PONG || fail "Redis indisponível"

STEP=api; status api running "iniciando API"
PID_FILE="$RUN_DIR/api.pid"
if [ -f "$PID_FILE" ] && ! kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  log "a execução anterior da API não está mais rodando; iniciando de novo"
  rm -f "$PID_FILE"
fi
if [ -f "$PID_FILE" ]; then
  if [ "$(cat "$RUN_DIR/api.running.commit" 2>/dev/null)" = "$COMMIT" ]; then
    log "API já em execução (pid $(cat "$PID_FILE"))"
  else
    log "nova versão: encerrando API anterior de forma ordenada"
    kill -TERM "$(cat "$PID_FILE")"; for _ in $(seq 1 30); do kill -0 "$(cat "$PID_FILE")" 2>/dev/null || break; sleep 1; done
    rm -f "$PID_FILE"
  fi
fi
if [ ! -f "$PID_FILE" ]; then
  [ -f "$LOG_DIR/api.log" ] && mv "$LOG_DIR/api.log" "$LOG_DIR/api.$(date -u +%Y%m%dT%H%M%SZ).log"
  find "$LOG_DIR" -name 'api.*.log' -mtime +7 -delete # only rotated logs; never data
  nohup env WORKERS_ENABLED="${WORKERS_ENABLED:-true}" SECURE_COOKIES=false \
    java -XX:MaxRAMPercentage=50 -jar "$JAR" > "$LOG_DIR/api.log" 2>&1 &
  echo $! > "$PID_FILE"; echo "$COMMIT" > "$RUN_DIR/api.running.commit"
fi

STEP=health; status health running "verificando saúde"
log "aguardando a API responder (até 3 min; a primeira subida aplica as migrações do banco)…"
healthy=false
for i in $(seq 1 90); do
  if curl -fs -o /dev/null localhost:8080/actuator/health; then healthy=true; break; fi
  if ! kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    echo "------ a API encerrou ao iniciar; últimas linhas de $LOG_DIR/api.log ------" >&2
    tail -n 60 "$LOG_DIR/api.log" >&2 || true
    echo "----------------------------------------" >&2
    rm -f "$PID_FILE"
    fail "a API não conseguiu iniciar (erro acima)"
  fi
  [ $((i % 10)) -eq 0 ] && log "ainda iniciando… ($((i * 2)) s)"
  sleep 2
done
if [ "$healthy" != true ]; then
  echo "------ últimas linhas de $LOG_DIR/api.log ------" >&2
  tail -n 60 "$LOG_DIR/api.log" >&2 || true
  echo "----------------------------------------" >&2
  fail "API não ficou saudável em 180 s"
fi
curl -fsS -H "X-Control-Token: $CONTROL_STATUS_TOKEN" localhost:8080/internal/status >"$RUN_DIR/internal-status.json" \
  || log "aviso: /internal/status não respondeu"
STEP=ready; status ready ok "API ${COMMIT:0:7} saudável"
log "pronto. Logs: $LOG_DIR"

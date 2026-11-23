#!/usr/bin/env bash
# postCreateCommand — runs once when the codespace container is created.
# Checks toolchain versions and installs dependencies. Never touches databases or volumes.
set -euo pipefail
# shellcheck source=infrastructure/codespaces/lib.sh
source "$(dirname "$0")/lib.sh"
STEP=setup

mvn_java_flags >/dev/null  # Java 21+ (25 recomendado); falha com mensagem clara se mais antigo
command -v mvn >/dev/null || fail "Maven não encontrado"
node -v | grep -q '^v22' || fail "Node 22 não encontrado"
command -v pnpm >/dev/null || corepack enable
command -v pg_isready >/dev/null || { sudo apt-get update -qq && sudo apt-get install -y -qq postgresql-client redis-tools >/dev/null; }

# Homologation-only runtime secrets, generated once and kept outside the git checkout (survive stop/start).
if [ ! -f "$SECRETS_FILE" ]; then
  umask 077
  {
    echo "JWT_SECRET=$(openssl rand -base64 48)"
    echo "CREDENTIALS_ENCRYPTION_KEY=$(openssl rand -base64 32)"
    echo "CONTROL_STATUS_TOKEN=$(openssl rand -base64 36 | tr '+/' '-_' | tr -d '=')"
    echo "CONTROL_ADMIN_TOKEN=$(openssl rand -base64 36 | tr '+/' '-_' | tr -d '=')"
  } > "$SECRETS_FILE"
  log "segredos de homologação gerados em $SECRETS_FILE (não versionado)"
fi

cd "$REPO_DIR"
pnpm install --frozen-lockfile
build_api "$LOG_DIR/build.log" || fail "build inicial da API falhou (erro acima; log em $LOG_DIR/build.log)"
log "setup concluído"

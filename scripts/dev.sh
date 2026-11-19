#!/usr/bin/env bash
# One-command local development: infra in Docker, API (dev profile: emails to log) and web from source.
set -euo pipefail
docker compose up -d postgres redis
export SPRING_PROFILES_ACTIVE=dev
export JWT_SECRET="${JWT_SECRET:-$(openssl rand -base64 48)}"
export CREDENTIALS_ENCRYPTION_KEY="${CREDENTIALS_ENCRYPTION_KEY:-$(openssl rand -base64 32)}"
# The build targets Java 25; with an older local JDK (21+) override the release level for development only.
JV=$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')
EXTRA=""; [ "${JV:-25}" -lt 25 ] && EXTRA="-Djava.version=${JV}"
(cd apps/api && mvn -q $EXTRA spring-boot:run) &
pnpm install && pnpm dev:web

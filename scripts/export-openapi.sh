#!/usr/bin/env bash
# Regenerates packages/contracts/openapi.json from a running API and the typed client from it.
set -euo pipefail
API="${API_URL:-http://localhost:8080}"
curl -fsS "$API/v3/api-docs" | python3 -m json.tool > packages/contracts/openapi.json
pnpm --filter @vendocas/api-client generate
echo "contract + client regenerated"

#!/usr/bin/env bash
# Prints the homologation API admin token so the owner can register it ONCE in the Control Center vault
# (Credenciais → API Vendocas → Teste/homologação → CONTROL_ADMIN_TOKEN). It allows changing environment, Stripe mode and
# fiscal issuance from the Control Center. Run it yourself in the codespace terminal; it is never logged.
set -euo pipefail
# shellcheck source=infrastructure/codespaces/lib.sh
source "$(dirname "$0")/lib.sh"
if [ -n "${CONTROL_ADMIN_TOKEN:-}" ]; then echo "$CONTROL_ADMIN_TOKEN"
else grep '^CONTROL_ADMIN_TOKEN=' "$SECRETS_FILE" | cut -d= -f2-; fi

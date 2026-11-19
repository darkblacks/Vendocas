#!/usr/bin/env bash
# Prints the homologation API status token so the owner can register it ONCE in the Control Center vault
# (provider "API Vendocas (homologação)"). Run it yourself in the codespace terminal; it is never logged.
set -euo pipefail
# shellcheck source=infrastructure/codespaces/lib.sh
source "$(dirname "$0")/lib.sh"
if [ -n "${CONTROL_STATUS_TOKEN:-}" ]; then echo "$CONTROL_STATUS_TOKEN"   # defined as a Codespaces secret
else grep '^CONTROL_STATUS_TOKEN=' "$SECRETS_FILE" | cut -d= -f2-; fi

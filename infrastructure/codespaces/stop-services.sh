#!/usr/bin/env bash
# Graceful API shutdown inside the codespace (data is kept). Codespace stop itself is done by GitHub/Control Center.
set -euo pipefail
# shellcheck source=infrastructure/codespaces/lib.sh
source "$(dirname "$0")/lib.sh"
STEP=stop
if [ -f "$RUN_DIR/api.pid" ] && kill -0 "$(cat "$RUN_DIR/api.pid")" 2>/dev/null; then
  kill -TERM "$(cat "$RUN_DIR/api.pid")" && log "API sinalizada para encerrar"
fi
rm -f "$RUN_DIR/api.pid"
status stopped ok "API parada manualmente"

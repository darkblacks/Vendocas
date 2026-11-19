import { schedulerTick } from "../../src/server/codespaces";
import { oplog } from "../../src/server/audit";
import { ConfigError } from "../../src/server/env";

/**
 * Netlify Scheduled Function (cron "*\/5 * * * *", configured in netlify.toml). It is not reachable as a public URL in
 * production. Each run leases due sessions (FOR UPDATE SKIP LOCKED), confirms state with GitHub and requests a stop
 * once the session deadline has passed. It never starts a codespace and never sleeps waiting for the deadline.
 */
export default async () => {
  try {
    const r = await schedulerTick();
    if (r.checked) await oplog("scheduler", "INFO", `Scheduler checked ${r.checked} session(s)`, { actions: r.actions });
    return new Response(null, { status: 204 });
  } catch (e) {
    // Before /bootstrap has stored the database there is nothing to supervise.
    if (e instanceof ConfigError && ["DATABASE_NOT_CONFIGURED", "SETUP_NOT_CONFIGURED"].includes(e.code)) return new Response(null, { status: 204 });
    console.error("[scheduler] failed", (e as Error).name);
    return new Response(null, { status: 500 });
  }
};

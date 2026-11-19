// Applies the Control Center schema explicitly (the app also migrates automatically on first use and during /bootstrap).
// Usage: CONTROL_DATABASE_URL=postgres://... node --experimental-strip-types scripts/migrate.ts
// Same strategy as src/server/db.ts: one transaction + transaction-level advisory lock (works through Neon's pooler).
import pg from "pg";
import { MIGRATIONS } from "../src/server/migrations.ts";

const url = process.env.CONTROL_DATABASE_URL;
if (!url) { console.error("CONTROL_DATABASE_URL is required"); process.exit(1); }
const c = new pg.Client({ connectionString: url });
await c.connect();
try {
  await c.query("BEGIN");
  await c.query("SELECT pg_advisory_xact_lock(727001)");
  await c.query("CREATE TABLE IF NOT EXISTS cc_schema_migrations (id int PRIMARY KEY, name text NOT NULL, applied_at timestamptz NOT NULL DEFAULT now())");
  const done = new Set((await c.query("SELECT id FROM cc_schema_migrations")).rows.map((r) => r.id));
  for (const m of MIGRATIONS) {
    if (done.has(m.id)) continue;
    await c.query(m.sql);
    await c.query("INSERT INTO cc_schema_migrations (id, name) VALUES ($1, $2)", [m.id, m.name]);
    console.log(`applying ${m.id} ${m.name}`);
  }
  await c.query("COMMIT");
  console.log("schema up to date");
} catch (e) {
  await c.query("ROLLBACK").catch(() => undefined);
  console.error("migration failed:", (e as Error).message);
  process.exitCode = 1;
} finally {
  await c.end();
}

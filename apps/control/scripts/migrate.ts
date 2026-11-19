// Applies the Control Center schema explicitly (the app also migrates automatically on first use).
// Usage: CONTROL_DATABASE_URL=postgres://... node --experimental-strip-types scripts/migrate.ts
import pg from "pg";
import { MIGRATIONS } from "../src/server/migrations.ts";

const url = process.env.CONTROL_DATABASE_URL;
if (!url) { console.error("CONTROL_DATABASE_URL is required"); process.exit(1); }
const c = new pg.Client({ connectionString: url });
await c.connect();
try {
  await c.query("SELECT pg_advisory_lock(727001)");
  await c.query("CREATE TABLE IF NOT EXISTS cc_schema_migrations (id int PRIMARY KEY, name text NOT NULL, applied_at timestamptz NOT NULL DEFAULT now())");
  const done = new Set((await c.query("SELECT id FROM cc_schema_migrations")).rows.map((r) => r.id));
  for (const m of MIGRATIONS) {
    if (done.has(m.id)) continue;
    await c.query("BEGIN");
    await c.query(m.sql);
    await c.query("INSERT INTO cc_schema_migrations (id, name) VALUES ($1, $2)", [m.id, m.name]);
    await c.query("COMMIT");
    console.log(`applied ${m.id} ${m.name}`);
  }
  console.log("schema up to date");
} finally {
  await c.end();
}

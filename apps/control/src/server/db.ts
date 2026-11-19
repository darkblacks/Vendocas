import pg from "pg";
import { env } from "./env";
import { MIGRATIONS } from "./migrations";

let pool: pg.Pool | null = null;
let ready: Promise<void> | null = null;

export function getPool(): pg.Pool {
  if (!pool) {
    const url = env.databaseUrl();
    pool = new pg.Pool({
      connectionString: url,
      max: Number(process.env.CONTROL_DB_POOL_MAX ?? 3),
      idleTimeoutMillis: 10_000,
      connectionTimeoutMillis: 8_000,
      // TLS: governed by sslmode in the URL (managed Postgres such as Neon/Supabase requires sslmode=require).
    });
  }
  return pool;
}

/** Applies pending migrations once per process (serverless cold start), serialized with an advisory lock. */
export async function migrate(p: pg.Pool = getPool()): Promise<void> {
  const c = await p.connect();
  try {
    await c.query("SELECT pg_advisory_lock(727001)");
    await c.query("CREATE TABLE IF NOT EXISTS cc_schema_migrations (id int PRIMARY KEY, name text NOT NULL, applied_at timestamptz NOT NULL DEFAULT now())");
    const done = new Set((await c.query<{ id: number }>("SELECT id FROM cc_schema_migrations")).rows.map((r) => r.id));
    for (const m of MIGRATIONS) {
      if (done.has(m.id)) continue;
      await c.query("BEGIN");
      try {
        await c.query(m.sql);
        await c.query("INSERT INTO cc_schema_migrations (id, name) VALUES ($1, $2)", [m.id, m.name]);
        await c.query("COMMIT");
      } catch (e) {
        await c.query("ROLLBACK");
        throw e;
      }
    }
  } finally {
    await c.query("SELECT pg_advisory_unlock(727001)").catch(() => undefined);
    c.release();
  }
}

export async function db(): Promise<pg.Pool> {
  const p = getPool();
  if (!ready) ready = migrate(p).catch((e) => { ready = null; throw e; });
  await ready;
  return p;
}

export async function tx<T>(fn: (c: pg.PoolClient) => Promise<T>): Promise<T> {
  const p = await db();
  const c = await p.connect();
  try {
    await c.query("BEGIN");
    const r = await fn(c);
    await c.query("COMMIT");
    return r;
  } catch (e) {
    await c.query("ROLLBACK").catch(() => undefined);
    throw e;
  } finally {
    c.release();
  }
}

/** For tests: replace the pool. */
export function __setPool(p: pg.Pool | null) {
  pool = p;
  ready = null;
}

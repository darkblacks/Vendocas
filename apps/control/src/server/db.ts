import pg from "pg";
import { resolveDatabase } from "./database-config";
import { MIGRATIONS } from "./migrations";

let pool: pg.Pool | null = null;
let poolUrl: string | null = null;
let locked = false;
let ready: Promise<void> | null = null;

function newPool(url: string) {
  const p = new pg.Pool({
    connectionString: url,
    max: Number(process.env.CONTROL_DB_POOL_MAX ?? 3),
    idleTimeoutMillis: 10_000,
    connectionTimeoutMillis: 8_000,
    // TLS is governed by sslmode in the URL (Neon URLs carry sslmode=require).
  });
  p.on("error", () => undefined); // idle-client errors (e.g. Neon scale-to-zero) must not crash the function
  return p;
}

/**
 * Pool for the Control Center database. The URL comes from CONTROL_DATABASE_URL or from the encrypted config saved by
 * /bootstrap. Until bootstrap is locked the stored URL is re-read on every call, so a corrected URL is picked up by
 * every warm instance; afterwards it is cached for the life of the instance.
 */
export async function getPool(): Promise<pg.Pool> {
  if (pool && locked) return pool;
  const d = await resolveDatabase();
  if (!pool || poolUrl !== d.url) {
    const old = pool;
    pool = newPool(d.url);
    poolUrl = d.url;
    ready = null;
    if (old) void old.end().catch(() => undefined);
  }
  locked = d.locked;
  return pool;
}

/**
 * Applies pending migrations in ONE transaction guarded by a transaction-level advisory lock. Transaction-scoped locks
 * work through PgBouncer/Neon pooled connections (session locks do not), and a failed migration rolls back entirely.
 */
export async function migrate(p: pg.Pool): Promise<void> {
  const c = await p.connect();
  try {
    await c.query("BEGIN");
    await c.query("SELECT pg_advisory_xact_lock(727001)");
    await c.query("CREATE TABLE IF NOT EXISTS cc_schema_migrations (id int PRIMARY KEY, name text NOT NULL, applied_at timestamptz NOT NULL DEFAULT now())");
    const done = new Set((await c.query<{ id: number }>("SELECT id FROM cc_schema_migrations")).rows.map((r) => r.id));
    for (const m of MIGRATIONS) {
      if (done.has(m.id)) continue;
      await c.query(m.sql);
      await c.query("INSERT INTO cc_schema_migrations (id, name) VALUES ($1, $2)", [m.id, m.name]);
    }
    await c.query("COMMIT");
  } catch (e) {
    await c.query("ROLLBACK").catch(() => undefined);
    throw e;
  } finally {
    c.release();
  }
}

export async function db(): Promise<pg.Pool> {
  const p = await getPool();
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

/** Drops the cached pool (after the bootstrap stores a new URL, and in tests). */
export function resetPool() {
  const old = pool;
  pool = null; poolUrl = null; locked = false; ready = null;
  if (old) void old.end().catch(() => undefined);
}

/** For tests: replace the pool. */
export function __setPool(p: pg.Pool | null) {
  if (p === null) return resetPool();
  pool = p; poolUrl = null; locked = true; ready = null;
}

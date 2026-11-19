import { isIP } from "node:net";
import pg from "pg";
import { activeKey, open, seal, sha256 } from "./crypto";
import { configStore } from "./config-store";
import { ConfigError, env } from "./env";
import { HttpError } from "./http";

/**
 * The Control Center's own database connection string (e.g. a Neon URL), pasted once during bootstrap.
 * Stored AES-256-GCM encrypted with the master key (env, never in the store) in the external config store.
 * Never returned to the browser, never logged; only a short fingerprint identifies it.
 *
 * CONTROL_DATABASE_URL (Netlify env var) still works and takes precedence — used for tests, scripts and as an
 * emergency override if the stored URL must be replaced after bootstrap was locked.
 */
const KEY = "database";
const AAD = "cc_database_url";

export interface StoredDatabaseConfig {
  v: 1;
  sealed: { ciphertext: string; nonce: string; keyVersion: string };
  fingerprint: string;
  provider: string;
  serverVersion: string;
  configuredAt: string;
  lockedAt: string | null;
}

export type DatabaseSource = { url: string; source: "env" | "store"; locked: boolean };

async function read(): Promise<StoredDatabaseConfig | null> {
  const raw = await (await configStore()).get(KEY);
  return raw ? (JSON.parse(raw) as StoredDatabaseConfig) : null;
}
async function write(c: StoredDatabaseConfig) { await (await configStore()).set(KEY, JSON.stringify(c)); }

export async function storedDatabaseConfig() { return read(); }

/** Resolves the connection string: env override first, then the encrypted stored one. */
export async function resolveDatabase(): Promise<DatabaseSource> {
  const fromEnv = process.env.CONTROL_DATABASE_URL?.trim();
  if (fromEnv) return { url: fromEnv, source: "env", locked: true };
  const c = await read();
  if (!c) throw new ConfigError("DATABASE_NOT_CONFIGURED", "The database has not been configured yet (open /bootstrap)");
  return { url: open(c.sealed, AAD), source: "store", locked: !!c.lockedAt };
}

const FORBIDDEN_PARAMS = ["host", "hostaddr", "sslcert", "sslkey", "sslrootcert", "sslpassword", "passfile", "service", "servicefile"];
const SECURE_SSLMODES = ["require", "verify-ca", "verify-full"];

/** Validates a pasted PostgreSQL URL without ever echoing it back. Throws HttpError(400) with a reason code. */
export function validateConnectionString(input: string, production = env.isProduction()): URL {
  const bad = (reason: string, message: string) => new HttpError(400, "INVALID_CONNECTION_STRING", message, { reason });
  const s = input.trim();
  if (!s || s.length > 2048 || /\s/.test(s)) throw bad("FORMAT", "Paste the full connection string on a single line");
  let u: URL;
  try { u = new URL(s); } catch { throw bad("FORMAT", "This is not a valid connection string (expected postgresql://…)"); }
  if (u.protocol !== "postgres:" && u.protocol !== "postgresql:") throw bad("PROTOCOL", "The connection string must start with postgresql:// or postgres://");
  if (!u.hostname) throw bad("HOST", "The connection string has no host");
  if (!u.username || !u.password) throw bad("CREDENTIALS", "The connection string must include the user and the password");
  if (u.pathname.replace(/^\//, "").length === 0) throw bad("DATABASE", "The connection string must name the database (…/dbname)");
  for (const p of u.searchParams.keys()) {
    if (FORBIDDEN_PARAMS.includes(p.toLowerCase())) throw bad("PARAMETER", `Parameter "${p}" is not allowed here`);
  }
  const host = u.hostname.replace(/^\[|\]$/g, "");
  const local = host === "localhost" || host === "127.0.0.1" || host === "::1";
  const sslmode = u.searchParams.get("sslmode");
  if (production || !local) {
    if (!sslmode || !SECURE_SSLMODES.includes(sslmode)) throw bad("TLS", "TLS is required: the connection string must contain sslmode=require (Neon includes it)");
    if (local || (isIP(host) && isPrivateIp(host))) throw bad("HOST", "Local or private network addresses are not allowed");
  }
  return u;
}

function isPrivateIp(ip: string) {
  return /^(10\.|127\.|169\.254\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|0\.)/.test(ip) || ip === "::1" || /^f[cd]/i.test(ip) || /^fe80/i.test(ip);
}

/** Maps driver errors to safe, actionable codes (driver messages can contain user/host names: never forwarded). */
export function mapConnectionError(e: unknown): HttpError {
  const err = e as { code?: string; message?: string };
  const m = (code: string, message: string) => new HttpError(400, code, message);
  switch (err.code) {
    case "28P01": case "28000": return m("DB_AUTH_FAILED", "The database rejected the user or password");
    case "3D000": return m("DB_NOT_FOUND", "The database named in the connection string does not exist");
    case "42501": return m("DB_PERMISSION_DENIED", "The user cannot create tables in this database");
    case "ENOTFOUND": case "EAI_AGAIN": return m("DB_HOST_NOT_FOUND", "The database host was not found");
    case "ECONNREFUSED": case "ETIMEDOUT": case "ECONNRESET": case "EHOSTUNREACH": return m("DB_UNREACHABLE", "Could not reach the database server");
  }
  const msg = err.message ?? "";
  if (/timeout/i.test(msg)) return m("DB_UNREACHABLE", "Could not reach the database server (timeout)");
  if (/ssl|tls|certificate/i.test(msg)) return m("DB_TLS_FAILED", "TLS negotiation with the database failed");
  return m("DB_CONNECTION_FAILED", "Could not connect to the database");
}

export interface ProbeResult { serverVersion: string; provider: string; alreadyInitialized: boolean }

/**
 * Connects with the candidate URL, checks the server version, applies the Control Center migrations (proves the user
 * can create tables) and reports whether this database already finished a bootstrap.
 */
export async function probeDatabase(url: URL, migrate: (p: pg.Pool) => Promise<void>): Promise<ProbeResult> {
  const pool = new pg.Pool({ connectionString: url.toString(), max: 1, connectionTimeoutMillis: 10_000, idleTimeoutMillis: 1_000 });
  pool.on("error", () => undefined);
  try {
    const info = (await pool.query<{ v: number; ver: string }>("SELECT current_setting('server_version_num')::int AS v, current_setting('server_version') AS ver")).rows[0]!;
    if (info.v < 140000) throw new HttpError(400, "DB_VERSION_UNSUPPORTED", "PostgreSQL 14 or newer is required");
    await migrate(pool);
    const done = await pool.query("SELECT bootstrap_completed_at FROM cc_system_state WHERE id = 1");
    return {
      serverVersion: info.ver.split(" ")[0]!,
      provider: /\.neon\.tech$/i.test(url.hostname) ? "Neon" : "PostgreSQL",
      alreadyInitialized: !!done.rows[0]?.bootstrap_completed_at,
    };
  } catch (e) {
    throw e instanceof HttpError ? e : mapConnectionError(e);
  } finally {
    await pool.end().catch(() => undefined);
  }
}

export async function saveDatabaseConfig(url: URL, probe: ProbeResult): Promise<StoredDatabaseConfig> {
  const c: StoredDatabaseConfig = {
    v: 1,
    sealed: seal(url.toString(), AAD),
    fingerprint: sha256(url.toString()).slice(0, 12),
    provider: probe.provider,
    serverVersion: probe.serverVersion,
    configuredAt: new Date().toISOString(),
    // A database that already finished bootstrap (re-linking after the stored config was lost) is locked at once.
    lockedAt: probe.alreadyInitialized ? new Date().toISOString() : null,
  };
  await write(c);
  return c;
}

/** Called when the owner finishes MFA enrollment: the stored database can no longer be replaced from /bootstrap. */
export async function lockDatabaseConfig(): Promise<void> {
  const c = await read();
  if (c && !c.lockedAt) await write({ ...c, lockedAt: new Date().toISOString() });
}

/** Master-key rotation: re-encrypts the stored connection string with the active key. */
export async function rotateDatabaseConfig(): Promise<boolean> {
  const c = await read();
  const key = activeKey();
  if (!c || c.sealed.keyVersion === key.version) return false;
  await write({ ...c, sealed: seal(open(c.sealed, AAD), AAD, key) });
  return true;
}

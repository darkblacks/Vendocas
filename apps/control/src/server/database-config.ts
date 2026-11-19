import { lookup } from "node:dns/promises";
import { isIP } from "node:net";
import pg from "pg";
import { activeKey, keyring, open, seal, sha256 } from "./crypto";
import { configStore } from "./config-store";
import { ConfigError, env } from "./env";
import { HttpError } from "./http";

/**
 * The Control Center's own PostgreSQL (Neon) connection string, pasted once during installation.
 * Stored AES-256-GCM encrypted with the "config" key derived from CONTROL_SETUP_KEY, in the external config store.
 * Never returned to the browser, never logged; a short fingerprint identifies it in audit records.
 * CONTROL_DATABASE_URL, if present, takes precedence (tests, scripts, emergency override) — it is never required.
 */
const KEY = "database";
const AAD = "cc_database_url";

export const DB_CONNECT_TIMEOUT_MS = 6000;
export const DB_QUERY_TIMEOUT_MS = 6000;

export interface StoredDatabaseConfig {
  v: 1;
  sealed: { ciphertext: string; nonce: string; keyVersion: string };
  fingerprint: string;
  provider: string;
  serverVersion: string;
  configuredAt: string;
  /** Installation completed: the stored database can no longer be replaced through /bootstrap. */
  lockedAt: string | null;
}

export type DatabaseSource = { url: string; source: "env" | "store"; locked: boolean };

async function read(): Promise<StoredDatabaseConfig | null> {
  const raw = await (await configStore()).get(KEY);
  if (!raw) return null;
  try { return JSON.parse(raw) as StoredDatabaseConfig; } catch { throw new ConfigError("CONFIG_CORRUPT", "The stored installation configuration is unreadable"); }
}
async function write(c: StoredDatabaseConfig) { await (await configStore()).set(KEY, JSON.stringify(c)); }

export const storedDatabaseConfig = read;
export const databaseFromEnv = () => !!process.env.CONTROL_DATABASE_URL?.trim();

/** Resolves the connection string: env override first, then the encrypted stored one. */
export async function resolveDatabase(): Promise<DatabaseSource> {
  const fromEnv = process.env.CONTROL_DATABASE_URL?.trim();
  if (fromEnv) return { url: fromEnv, source: "env", locked: true };
  const c = await read();
  if (!c) throw new ConfigError("DATABASE_NOT_CONFIGURED", "The database has not been configured yet (open /bootstrap)");
  let url: string;
  try { url = open(c.sealed, AAD, "config"); } catch (e) {
    if (e instanceof ConfigError) throw e;
    throw new ConfigError("CONFIG_DECRYPT_FAILED", "The stored connection string could not be decrypted with CONTROL_SETUP_KEY");
  }
  return { url, source: "store", locked: !!c.lockedAt };
}

// ------------------------------------------------------------------ validation (no network)
const FORBIDDEN_PARAMS = ["host", "hostaddr", "port", "dbname", "user", "password", "sslcert", "sslkey", "sslrootcert", "sslcrl", "sslpassword", "passfile", "service", "servicefile", "target_session_attrs"];
const SECURE_SSLMODES = ["require", "verify-ca", "verify-full"];
const ALLOWED_PORTS = [5432, 6432, 6543];

const invalid = (reason: string, message: string) => new HttpError(400, "INVALID_CONNECTION_STRING", message, { reason });

const isLocalName = (h: string) => h === "localhost" || h.endsWith(".localhost") || h === "127.0.0.1" || h === "::1";

/** Syntax and policy checks. Never echoes the input. `production` = strict mode (TLS, public hosts, known ports). */
export function validateConnectionString(input: string, production = env.isProduction()): URL {
  const s = input.trim();
  if (!s || s.length > 2048 || /\s/.test(s)) throw invalid("FORMAT", "Paste the full connection string on a single line");
  let u: URL;
  try { u = new URL(s); } catch { throw invalid("FORMAT", "This is not a valid connection string (expected postgresql://…)"); }
  if (u.protocol !== "postgres:" && u.protocol !== "postgresql:") throw invalid("PROTOCOL", "The connection string must start with postgresql:// or postgres://");
  if (!u.hostname || u.hostname.includes(",")) throw invalid("HOST", "The connection string must contain exactly one host");
  if (!u.username || !u.password) throw invalid("CREDENTIALS", "The connection string must include the user and the password");
  if (u.pathname.replace(/^\//, "").length === 0) throw invalid("DATABASE", "The connection string must name the database (…/dbname)");
  for (const p of u.searchParams.keys()) {
    if (FORBIDDEN_PARAMS.includes(p.toLowerCase())) throw invalid("PARAMETER", `Parameter "${p}" is not allowed here`);
  }
  const host = u.hostname.replace(/^\[|\]$/g, "");
  const port = u.port ? Number(u.port) : 5432;
  const local = isLocalName(host);
  if (production || !local) {
    const sslmode = u.searchParams.get("sslmode");
    if (!sslmode || !SECURE_SSLMODES.includes(sslmode)) throw invalid("TLS", "TLS is required: the connection string must contain sslmode=require (Neon includes it)");
    if (local || (isIP(host) && isBlockedIp(host))) throw invalid("HOST", "Local or private network addresses are not allowed");
    if (!ALLOWED_PORTS.includes(port)) throw invalid("PORT", `Only PostgreSQL ports ${ALLOWED_PORTS.join(", ")} are allowed`);
  }
  return u;
}

/** Loopback, private, link-local (cloud metadata), CGNAT, multicast, reserved — IPv4 and IPv6 (incl. mapped/NAT64). */
export function isBlockedIp(ip: string): boolean {
  if (isIP(ip) === 4) {
    const [a, b] = ip.split(".").map(Number) as [number, number];
    return a === 0 || a === 10 || a === 127 || a >= 224 || (a === 100 && b >= 64 && b <= 127) || (a === 169 && b === 254) ||
      (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) || (a === 192 && b === 0) || (a === 198 && (b === 18 || b === 19));
  }
  const v = ip.toLowerCase();
  const embedded = v.match(/^(?:::ffff:|64:ff9b::)(\d+\.\d+\.\d+\.\d+)$/);
  if (embedded) return isBlockedIp(embedded[1]!);
  return v === "::" || v === "::1" || /^f[cd]/.test(v) || /^fe[89ab]/.test(v) || /^ff/.test(v) || v.startsWith("::ffff:") || v.startsWith("64:ff9b:");
}

// ------------------------------------------------------------------ resolution (SSRF) + pg config
type Resolver = (host: string) => Promise<string[]>;
let resolver: Resolver = async (host) => (await lookup(host, { all: true, verbatim: true })).map((a) => a.address);
/** Tests: fake DNS. */
export function __setResolver(r: Resolver | null) { resolver = r ?? (async (host) => (await lookup(host, { all: true, verbatim: true })).map((a) => a.address)); }

/**
 * Builds the driver config. In strict mode the host is resolved once, EVERY address must be public, and the connection
 * is pinned to the checked IP (no DNS-rebinding window) while TLS still verifies the certificate for — and sends SNI
 * with — the original host name (Neon routes by SNI).
 */
export async function pgConfig(url: URL, production = env.isProduction()): Promise<pg.PoolConfig> {
  const host = url.hostname.replace(/^\[|\]$/g, "");
  const strict = production || !isLocalName(host);
  let connectHost = host;
  if (strict) {
    let addrs: string[];
    try {
      addrs = isIP(host) ? [host] : await Promise.race([resolver(host), new Promise<never>((_, rej) => setTimeout(() => rej(Object.assign(new Error("dns timeout"), { code: "ETIMEDOUT" })), 3000))]);
    } catch (e) {
      throw mapConnectionError(e);
    }
    if (!addrs.length) throw new HttpError(400, "DB_HOST_NOT_FOUND", "The database host was not found");
    if (addrs.some(isBlockedIp)) throw invalid("HOST", "The database host resolves to a private or local network address");
    connectHost = addrs.find((a) => isIP(a) === 4) ?? addrs[0]!;
  }
  const sslmode = url.searchParams.get("sslmode");
  const tls = strict || (sslmode !== null && sslmode !== "disable");
  return {
    host: connectHost,
    port: url.port ? Number(url.port) : 5432,
    user: decodeURIComponent(url.username),
    password: decodeURIComponent(url.password),
    database: decodeURIComponent(url.pathname.replace(/^\//, "")),
    ssl: tls ? { servername: isIP(host) ? undefined : host, rejectUnauthorized: strict } : false,
    options: url.searchParams.get("options") ?? undefined,
    application_name: url.searchParams.get("application_name") ?? "vendocas-control",
    enableChannelBinding: url.searchParams.get("channel_binding") === "require",
    connectionTimeoutMillis: DB_CONNECT_TIMEOUT_MS,
    query_timeout: DB_QUERY_TIMEOUT_MS,
  } as pg.PoolConfig;
}

/** Maps driver errors to safe, actionable codes (driver messages can contain user/host names: never forwarded). */
export function mapConnectionError(e: unknown): HttpError {
  if (e instanceof HttpError) return e;
  const err = e as { code?: string; message?: string };
  const m = (code: string, message: string) => new HttpError(400, code, message);
  switch (err.code) {
    case "28P01": case "28000": return m("DB_AUTH_FAILED", "The database rejected the user or password");
    case "3D000": return m("DB_NOT_FOUND", "The database named in the connection string does not exist");
    case "42501": return m("DB_PERMISSION_DENIED", "The user cannot create tables in this database");
    case "57014": case "55P03": return m("DB_TIMEOUT", "The database took too long to answer");
    case "ENOTFOUND": case "EAI_AGAIN": case "ENODATA": return m("DB_HOST_NOT_FOUND", "The database host was not found");
    case "ECONNREFUSED": case "ETIMEDOUT": case "ECONNRESET": case "EHOSTUNREACH": case "ENETUNREACH": return m("DB_UNREACHABLE", "Could not reach the database server");
  }
  const msg = err.message ?? "";
  if (/timeout|timed out/i.test(msg)) return m("DB_UNREACHABLE", "Could not reach the database server (timeout)");
  if (/ssl|tls|certificate|self.signed/i.test(msg)) return m("DB_TLS_FAILED", "TLS negotiation with the database failed");
  if (/endpoint.*(not found|could not)|SNI/i.test(msg)) return m("DB_ENDPOINT_NOT_FOUND", "Neon could not identify the endpoint; copy the connection string again");
  return m("DB_CONNECTION_FAILED", "Could not connect to the database");
}

// ------------------------------------------------------------------ probe / save
export interface ProbeResult { serverVersion: string; provider: string; canCreateTables: boolean; alreadyInitialized: boolean }

async function withPool<T>(url: URL, f: (p: pg.Pool) => Promise<T>): Promise<T> {
  const pool = new pg.Pool({ ...(await pgConfig(url)), max: 1, idleTimeoutMillis: 1000 });
  pool.on("error", () => undefined);
  try { return await f(pool); } catch (e) { throw mapConnectionError(e); } finally { await pool.end().catch(() => undefined); }
}

/** Read-only check: connectivity, TLS, version, CREATE privilege, existing installation. Writes nothing. */
export async function testConnection(url: URL): Promise<ProbeResult> {
  return withPool(url, async (pool) => {
    const r = (await pool.query<{ v: number; ver: string; can_create: boolean; installed: string | null }>(`
      SELECT current_setting('server_version_num')::int AS v, current_setting('server_version') AS ver,
             has_schema_privilege(current_user, 'public', 'CREATE') AS can_create,
             to_regclass('public.cc_system_state')::text AS installed`)).rows[0]!;
    if (r.v < 140000) throw new HttpError(400, "DB_VERSION_UNSUPPORTED", "PostgreSQL 14 or newer is required");
    let alreadyInitialized = false;
    if (r.installed) alreadyInitialized = !!(await pool.query("SELECT bootstrap_completed_at FROM cc_system_state WHERE id = 1")).rows[0]?.bootstrap_completed_at;
    return { serverVersion: r.ver.split(" ")[0]!, provider: /\.neon\.tech$/i.test(url.hostname) ? "Neon" : "PostgreSQL", canCreateTables: r.can_create, alreadyInitialized };
  });
}

/** Test + prepare the schema (migrations) + store encrypted. */
export async function prepareAndSave(url: URL, migrate: (p: pg.Pool) => Promise<void>): Promise<{ probe: ProbeResult; saved: StoredDatabaseConfig }> {
  const probe = await testConnection(url);
  if (!probe.canCreateTables && !probe.alreadyInitialized) throw new HttpError(400, "DB_PERMISSION_DENIED", "The user cannot create tables in this database");
  await withPool(url, migrate);
  const saved: StoredDatabaseConfig = {
    v: 1,
    sealed: seal(url.toString(), AAD, activeKey("config")),
    fingerprint: sha256(url.toString()).slice(0, 12),
    provider: probe.provider,
    serverVersion: probe.serverVersion,
    configuredAt: new Date().toISOString(),
    // A database that already completed installation (stored config lost and re-linked) is locked at once.
    lockedAt: probe.alreadyInitialized ? new Date().toISOString() : null,
  };
  await write(saved);
  return { probe, saved };
}

/** Called when the owner completes MFA. */
export async function lockDatabaseConfig(): Promise<void> {
  const c = await read();
  if (c && !c.lockedAt) await write({ ...c, lockedAt: new Date().toISOString() });
}

/** Setup-key rotation: re-encrypts the stored connection string with the current key. */
export async function rotateDatabaseConfig(): Promise<boolean> {
  const c = await read();
  const key = activeKey("config");
  if (!c || c.sealed.keyVersion === key.version) return false;
  if (!keyring("config").has(c.sealed.keyVersion)) throw new ConfigError("SETUP_KEY_MISMATCH", "Set CONTROL_SETUP_KEY_PREVIOUS to the old key to re-encrypt");
  await write({ ...c, sealed: seal(open(c.sealed, AAD, "config"), AAD, key) });
  return true;
}

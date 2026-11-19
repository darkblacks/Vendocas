import { audit, type Actor } from "./audit";
import { assertSameOrigin, createEnrollment, readCookie, type EnrollmentChallenge } from "./auth";
import { configStore, storeMode } from "./config-store";
import { hmac, installAuthKey, safeEqual, sha256, uuid } from "./crypto";
import { db, migrate, resetPool, tx } from "./db";
import {
  databaseFromEnv, lockDatabaseConfig, prepareAndSave, storedDatabaseConfig, testConnection, validateConnectionString,
  type ProbeResult, type StoredDatabaseConfig,
} from "./database-config";
import { ConfigError, env, MIN_SETUP_KEY_LENGTH, requireSetupKey } from "./env";
import { HttpError, bad, clientIp, deny } from "./http";
import { hashPassword, passwordProblem } from "./password";
import { memoryRateLimit } from "./rate-limit";

/**
 * First installation. Requires only two Netlify variables, CONTROL_SETUP_EMAIL and CONTROL_SETUP_KEY.
 *
 *   1. /api/bootstrap/session   e-mail + installation key  → short-lived signed setup session (no database needed)
 *   2. /api/bootstrap/database  PostgreSQL connection string → tested, schema prepared, stored encrypted (config store)
 *   3. /api/bootstrap/owner     name + password → owner in PENDING_MFA (no privileges yet)
 *   4. /api/auth/enroll         TOTP confirmation → owner ACTIVE, installation completed and locked (DB + config store)
 *
 * The e-mail alone proves nothing: the key must match, and permanent privileges only exist after MFA is completed.
 */
export type InstallStage = "DATABASE" | "OWNER" | "DONE";

const SETUP_TTL_SECONDS = 30 * 60;
export const setupCookieName = () => (env.isProduction() ? "__Host-vcc_setup" : "vcc_setup");
export function setupCookie(value: string, maxAge: number) {
  return `${setupCookieName()}=${value}; Path=/; HttpOnly; SameSite=Strict; Max-Age=${maxAge}${env.isProduction() ? "; Secure" : ""}`;
}
export const clearSetupCookie = () => setupCookie("", 0);

/** No I/O at all: safe to call on page load, answers instantly even with no database or store. */
export function installInfo() {
  return {
    setupKeyConfigured: !!env.setupKey(),
    setupEmailConfigured: !!env.setupEmail(),
    minKeyLength: MIN_SETUP_KEY_LENGTH,
    store: storeMode(),
    databaseFromEnvironment: databaseFromEnv(),
  };
}

// ------------------------------------------------------------------ setup session (stateless, HMAC-signed)
function signSetup(email: string): string {
  const payload = Buffer.from(JSON.stringify({ e: email, x: Math.floor(Date.now() / 1000) + SETUP_TTL_SECONDS, n: uuid() })).toString("base64url");
  return `${payload}.${hmac(installAuthKey(), `setup-session:${payload}`)}`;
}

/** Validates the setup session cookie (+ same-origin for mutations). Invalidated by key rotation or e-mail change. */
export function requireSetup(req: Request): { email: string } {
  requireSetupKey();
  const raw = readCookie(req, setupCookieName());
  const [payload, sig] = raw?.split(".") ?? [];
  if (!payload || !sig || !safeEqual(sig, hmac(installAuthKey(), `setup-session:${payload}`))) throw new HttpError(401, "SETUP_SESSION_REQUIRED", "Enter the installation e-mail and key again");
  let p: { e: string; x: number };
  try { p = JSON.parse(Buffer.from(payload, "base64url").toString("utf8")); } catch { throw new HttpError(401, "SETUP_SESSION_REQUIRED", "Enter the installation e-mail and key again"); }
  if (p.x < Date.now() / 1000 || p.e !== env.setupEmail()) throw new HttpError(401, "SETUP_SESSION_EXPIRED", "The installation session expired; enter the e-mail and key again");
  if (!["GET", "HEAD"].includes(req.method)) assertSameOrigin(req);
  return { email: p.e };
}

// ------------------------------------------------------------------ installation state
export interface InstallState {
  stage: InstallStage;
  database: { source: "env" | "store" | null; provider: string | null; serverVersion: string | null; locked: boolean; reachable: boolean | null; error: string | null };
}

/** Reads the (time-bounded) config store and, when a database exists, its completion flag. */
export async function installState(): Promise<InstallState> {
  const fromEnv = databaseFromEnv();
  const stored = fromEnv ? null : await storedDatabaseConfig();
  const database: InstallState["database"] = {
    source: fromEnv ? "env" : stored ? "store" : null, provider: stored?.provider ?? null, serverVersion: stored?.serverVersion ?? null,
    locked: !!stored?.lockedAt, reachable: null, error: null,
  };
  if (!fromEnv && !stored) return { stage: "DATABASE", database };
  if (stored?.lockedAt) return { stage: "DONE", database };
  try {
    const p = await db();
    database.reachable = true;
    const done = !!(await p.query("SELECT bootstrap_completed_at FROM cc_system_state WHERE id = 1")).rows[0]?.bootstrap_completed_at;
    return { stage: done ? "DONE" : "OWNER", database };
  } catch (e) {
    database.reachable = false;
    database.error = e instanceof HttpError || e instanceof ConfigError ? e.code : "DB_CONNECTION_FAILED";
    // The stored database is unreachable and installation is not locked: the owner may paste a corrected URL.
    return { stage: "DATABASE", database };
  }
}

// ------------------------------------------------------------------ step 1: e-mail + installation key
const failureKey = (ip: string) => `ratelimit-setup-${sha256(ip).slice(0, 16)}-${Math.floor(Date.now() / 900_000)}`;
const MAX_FAILURES_PER_WINDOW = 10;

/**
 * Cross-instance failure counter, read with a short budget so a slow store cannot push the request past the deadline
 * (the stage lookup that follows has its own bound). If unavailable, the per-instance limiter still applies, and the
 * answer to wrong credentials stays a plain 401 — store health is only revealed to holders of the right key.
 */
async function storedFailures(ip: string): Promise<number> {
  try {
    const store = await configStore();
    return await Promise.race([
      store.get(failureKey(ip)).then((v) => Number(v ?? 0) || 0),
      new Promise<number>((res) => setTimeout(() => res(0), 1500)),
    ]);
  } catch { return 0; }
}
async function recordFailure(ip: string, current: number) {
  try {
    const store = await configStore();
    await Promise.race([store.set(failureKey(ip), String(current + 1)), new Promise((res) => setTimeout(res, 1500))]);
  } catch { /* best effort */ }
}

export async function startSetupSession(req: Request, input: { email: string; key: string }): Promise<{ state: InstallState; cookie: string }> {
  const ip = clientIp(req);
  assertSameOrigin(req);
  const key = requireSetupKey();
  const expectedEmail = env.setupEmail();
  if (!expectedEmail) throw new ConfigError("SETUP_EMAIL_NOT_CONFIGURED", "CONTROL_SETUP_EMAIL is not configured on the server");
  memoryRateLimit(`setup:${ip}`, MAX_FAILURES_PER_WINDOW, 15 * 60_000);
  const failures = await storedFailures(ip);
  if (failures >= MAX_FAILURES_PER_WINDOW) throw new HttpError(429, "RATE_LIMITED", "Too many attempts, try again later", { retryAfterSeconds: 900 });

  // Both comparisons always run (no early exit); the key is compared through the install-auth HMAC.
  const k = installAuthKey();
  const emailOk = safeEqual(sha256(input.email.trim().toLowerCase()), sha256(expectedEmail));
  const keyOk = safeEqual(hmac(k, `verify:${input.key.trim()}`), hmac(k, `verify:${key}`));
  if (!emailOk || !keyOk) {
    await recordFailure(ip, failures);
    console.warn("[install] invalid installation credentials", { ip }); // never the e-mail or key
    await new Promise((r) => setTimeout(r, 300));
    throw new HttpError(401, "SETUP_CREDENTIALS_INVALID", "E-mail or installation key is incorrect");
  }
  const state = await installState();
  if (state.stage === "DONE") throw new HttpError(409, "INSTALLATION_COMPLETE", "This installation is already complete; sign in instead");
  return { state, cookie: setupCookie(signSetup(expectedEmail), SETUP_TTL_SECONDS) };
}

// ------------------------------------------------------------------ step 2: database
async function assertDatabaseReplaceable() {
  if (databaseFromEnv()) throw new HttpError(409, "DATABASE_FROM_ENV", "The database is defined by CONTROL_DATABASE_URL in the environment");
  const current = await storedDatabaseConfig();
  if (current?.lockedAt) throw deny("BOOTSTRAP_LOCKED", "Installation already completed");
  if (current) {
    // A correction is allowed before the owner exists — never once the stored database finished installation.
    let done = false;
    try { done = !!(await (await db()).query("SELECT bootstrap_completed_at FROM cc_system_state WHERE id = 1")).rows[0]?.bootstrap_completed_at; } catch { /* unreachable: replacing it is the point */ }
    if (done) { await lockDatabaseConfig(); throw deny("BOOTSTRAP_LOCKED", "Installation already completed"); }
  }
}

const summary = (p: ProbeResult) => ({ provider: p.provider, serverVersion: p.serverVersion, canCreateTables: p.canCreateTables, alreadyInitialized: p.alreadyInitialized });

/** "Testar conexão": read-only probe; nothing is stored. */
export async function testDatabase(req: Request, connectionString: string) {
  requireSetup(req);
  memoryRateLimit(`setup-db:${clientIp(req)}`, 30, 15 * 60_000);
  await assertDatabaseReplaceable();
  const url = validateConnectionString(connectionString);
  return { ok: true, ...summary(await testConnection(url)) };
}

/** "Salvar e continuar": probe, prepare the schema, store encrypted. */
export async function saveDatabase(req: Request, connectionString: string) {
  requireSetup(req);
  const ip = clientIp(req);
  memoryRateLimit(`setup-db:${ip}`, 30, 15 * 60_000);
  await assertDatabaseReplaceable();
  requireSetupKey();
  const url = validateConnectionString(connectionString);
  const { probe, saved } = await prepareAndSave(url, migrate);
  resetPool();
  await audit({ id: null, label: "installation", ip }, "bootstrap.database_configured", "SUCCESS", saved.fingerprint,
    { provider: probe.provider, serverVersion: probe.serverVersion, relinked: probe.alreadyInitialized });
  return { ok: true, stage: (probe.alreadyInitialized ? "DONE" : "OWNER") as InstallStage, ...summary(probe) };
}

// ------------------------------------------------------------------ step 3: owner (privileges only after MFA)
export async function createOwner(req: Request, input: { displayName: string; password: string }): Promise<EnrollmentChallenge> {
  const { email } = requireSetup(req);
  const ip = clientIp(req);
  const actor: Actor = { id: null, label: `installation:${email}`, ip };
  const problem = passwordProblem(input.password, email);
  if (problem) throw bad(problem);
  const hash = await hashPassword(input.password);
  return tx(async (c) => {
    // Row lock on the singleton state serializes concurrent installations and the completion in completeEnrollment.
    await c.query("INSERT INTO cc_system_state (id) VALUES (1) ON CONFLICT (id) DO NOTHING");
    const st = (await c.query("SELECT bootstrap_completed_at FROM cc_system_state WHERE id = 1 FOR UPDATE")).rows[0];
    if (st?.bootstrap_completed_at) { await audit(actor, "bootstrap.owner", "DENIED", null, { reason: "completed" }, c); throw deny("BOOTSTRAP_LOCKED", "Installation already completed"); }
    if ((await c.query("SELECT 1 FROM cc_users WHERE status = 'ACTIVE' LIMIT 1")).rowCount) throw deny("BOOTSTRAP_LOCKED", "An active administrator already exists");
    // An unfinished previous attempt (owner still waiting for MFA) is replaced.
    await c.query("DELETE FROM cc_users WHERE status = 'PENDING_MFA' AND role = 'OWNER'");
    const id = uuid();
    await c.query("INSERT INTO cc_users (id, email, display_name, password_hash, role, status) VALUES ($1,$2,$3,$4,'OWNER','PENDING_MFA')",
      [id, email, input.displayName.trim(), hash]);
    await audit(actor, "bootstrap.owner_created", "SUCCESS", id, {}, c);
    return createEnrollment(c, id, email);
  });
}

export type { StoredDatabaseConfig };

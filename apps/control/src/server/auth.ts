import QRCode from "qrcode";
import { audit, type Actor } from "./audit";
import { open, randomToken, safeEqual, seal, sha256, uuid } from "./crypto";
import { db, tx } from "./db";
import { lockDatabaseConfig } from "./database-config";
import { env } from "./env";
import { HttpError, bad, clientIp, deny } from "./http";
import { DUMMY_HASH, hashPassword, passwordProblem, verifyPassword } from "./password";
import { rateLimit } from "./rate-limit";
import { base32, newTotpSecret, otpauthUri, verifyTotp } from "./totp";
import { randomBytes } from "node:crypto";

export type Role = "OWNER" | "ADMIN" | "VIEWER";
const RANK: Record<Role, number> = { VIEWER: 1, ADMIN: 2, OWNER: 3 };

/** Persistent sessions: 30 days at most, ended after 7 days without use, token rotated every 12 h of use. */
export const SESSION_ABSOLUTE_DAYS = 30;
export const SESSION_IDLE_DAYS = 7;
export const ROTATE_AFTER_HOURS = 12;
/** After a rotation the previous token keeps working this long, so parallel requests and other tabs never log out. */
export const ROTATION_GRACE_SECONDS = 120;
/** A browser that completed MFA is trusted for this long (sign-in there = e-mail + password). */
export const DEVICE_TRUST_DAYS = 30;
/** Sensitive actions need the password again after this long (password only — no second factor). */
export const REAUTH_MINUTES = 15;
const MAX_FAILED = 8;

export interface User { id: string; email: string; displayName: string; role: Role; status: string }
export interface Session { id: string; userId: string; csrfToken: string; reauthAt: Date; expiresAt: Date }
export interface AuthContext { user: User; session: Session; actor: Actor }

export const cookieName = () => (env.isProduction() ? "__Host-vcc_session" : "vcc_session");
export const deviceCookieName = () => (env.isProduction() ? "__Host-vcc_device" : "vcc_device");

/**
 * HttpOnly + Secure (production) + host-only (__Host-). SameSite=Lax so opening the panel from a link in another site
 * (e-mail, GitHub, Netlify) still sends the cookie — with Strict it looked like a logout. Cross-site POST/PUT/DELETE are
 * still refused: Lax does not send the cookie on them, and every mutation also needs Origin + the X-CSRF-Token.
 */
function cookie(name: string, value: string, maxAgeSeconds: number): string {
  return `${name}=${value}; Path=/; HttpOnly; SameSite=Lax; Max-Age=${Math.max(0, Math.floor(maxAgeSeconds))}${env.isProduction() ? "; Secure" : ""}`;
}
export const sessionCookie = (token: string, maxAgeSeconds: number) => cookie(cookieName(), token, maxAgeSeconds);
export const clearSessionCookie = () => sessionCookie("", 0);
export const deviceCookie = (token: string, maxAgeSeconds: number) => cookie(deviceCookieName(), token, maxAgeSeconds);

const userAad = (userId: string) => `cc_user_mfa:${userId}`;
const challengeAad = (id: string) => `cc_challenge:${id}`;

export interface EnrollmentChallenge { enrollToken: string; secret: string; otpauthUri: string; qrDataUrl: string }

/** Starts a mandatory TOTP enrollment for a PENDING_MFA user (used by installation and by first login of admins). */
export async function createEnrollment(c: import("pg").PoolClient, userId: string, email: string): Promise<EnrollmentChallenge> {
  const token = randomToken();
  const id = uuid();
  const secret = newTotpSecret();
  const s = seal(secret, challengeAad(id));
  await c.query(`INSERT INTO cc_auth_challenges (id, user_id, purpose, token_hash, pending_secret_enc, expires_at)
                 VALUES ($1,$2,'MFA_ENROLL',$3,$4, now() + interval '20 minutes')`, [id, userId, sha256(token), JSON.stringify(s)]);
  const uri = otpauthUri(secret, email);
  return { enrollToken: token, secret, otpauthUri: uri, qrDataUrl: await QRCode.toDataURL(uri, { margin: 1, width: 220 }) };
}

/** Confirms the TOTP enrollment; activates the account, issues recovery codes (shown once) and, for the first owner, locks bootstrap. */
export async function completeEnrollment(req: Request, enrollToken: string, code: string) {
  const ip = clientIp(req);
  await rateLimit(`enroll:${ip}`, 20, 900);
  const result = await tx(async (c) => {
    const r = await c.query(`SELECT ch.id, ch.user_id, ch.pending_secret_enc, ch.attempts, u.email, u.role FROM cc_auth_challenges ch
                             JOIN cc_users u ON u.id = ch.user_id
                             WHERE ch.token_hash = $1 AND ch.purpose = 'MFA_ENROLL' AND ch.used_at IS NULL AND ch.expires_at > now() FOR UPDATE OF ch`,
      [sha256(enrollToken)]);
    const ch = r.rows[0];
    if (!ch) throw deny("CHALLENGE_INVALID", "Enrollment expired; start again");
    if (ch.role === "OWNER") {
      // Serialized with owner creation: only the owner created by the (single) installation can ever complete it.
      const st = (await c.query("SELECT bootstrap_completed_at, bootstrap_owner_id FROM cc_system_state WHERE id = 1 FOR UPDATE")).rows[0];
      if (st?.bootstrap_completed_at && st.bootstrap_owner_id !== ch.user_id) throw deny("BOOTSTRAP_LOCKED", "Installation already completed");
    }
    if (ch.attempts >= 5) throw deny("CHALLENGE_LOCKED", "Too many attempts; start again");
    const secret = open(JSON.parse(ch.pending_secret_enc), challengeAad(ch.id));
    if (!verifyTotp(secret, code)) {
      await c.query("UPDATE cc_auth_challenges SET attempts = attempts + 1 WHERE id = $1", [ch.id]);
      return { ok: false as const };
    }
    const sealed = seal(secret, userAad(ch.user_id));
    await c.query("UPDATE cc_users SET mfa_secret_enc = $2, mfa_enabled_at = now(), status = 'ACTIVE', updated_at = now() WHERE id = $1",
      [ch.user_id, JSON.stringify(sealed)]);
    await c.query("UPDATE cc_auth_challenges SET used_at = now() WHERE id = $1", [ch.id]);
    const codes = Array.from({ length: 10 }, () => base32(randomBytes(7)).slice(0, 10));
    await c.query("DELETE FROM cc_recovery_codes WHERE user_id = $1", [ch.user_id]);
    for (const code of codes) await c.query("INSERT INTO cc_recovery_codes (id, user_id, code_hash) VALUES ($1,$2,$3)", [uuid(), ch.user_id, sha256(code)]);
    if (ch.role === "OWNER") {
      await c.query("UPDATE cc_system_state SET bootstrap_completed_at = COALESCE(bootstrap_completed_at, now()), bootstrap_owner_id = COALESCE(bootstrap_owner_id, $1), updated_at = now() WHERE id = 1", [ch.user_id]);
    }
    await audit({ id: ch.user_id, label: ch.email, ip }, "mfa.enrolled", "SUCCESS", ch.user_id, {}, c);
    return { ok: true as const, userId: ch.user_id as string, recoveryCodes: codes, owner: ch.role === "OWNER" };
  });
  if (!result.ok) throw new HttpError(401, "MFA_INVALID", "Invalid code");
  if (result.owner) {
    // The database also records completion; this additionally freezes the stored connection string.
    await lockDatabaseConfig().catch((e) => console.error("[bootstrap] could not lock stored database config", (e as Error).name));
  }
  const session = await createSession(result.userId, req, { trustDevice: true });
  return { ...session, recoveryCodes: result.recoveryCodes };
}

// ------------------------------------------------------------------ login
export type LoginResult =
  | { step: "DONE"; cookies: string[]; csrfToken: string }
  | { step: "MFA"; challenge: string }
  | { step: "ENROLL"; enroll: EnrollmentChallenge };

/**
 * First step. Password always required. On a browser trusted for this user (MFA completed there in the last 30 days)
 * the session is created right away; on a new device the second factor is asked once.
 */
export async function login(req: Request, email: string, password: string): Promise<LoginResult> {
  const ip = clientIp(req);
  const norm = email.trim().toLowerCase();
  await rateLimit(`login-ip:${ip}`, 20, 900);
  await rateLimit(`login-user:${sha256(norm)}`, 10, 900);
  const p = await db();
  const r = await p.query("SELECT id, email, password_hash, status, locked_until, role FROM cc_users WHERE email = $1", [norm]);
  const u = r.rows[0];
  const actor: Actor = { id: u?.id ?? null, label: norm, ip };
  if (!u) {
    await verifyPassword(password, DUMMY_HASH);
    await audit(actor, "auth.login", "FAILURE", null, { reason: "unknown user" });
    throw new HttpError(401, "INVALID_CREDENTIALS", "Invalid email or password");
  }
  if (u.status === "DISABLED" || (u.locked_until && new Date(u.locked_until) > new Date())) {
    await verifyPassword(password, DUMMY_HASH);
    await audit(actor, "auth.login", "DENIED", u.id, { reason: u.status === "DISABLED" ? "disabled" : "locked" });
    throw new HttpError(401, "INVALID_CREDENTIALS", "Invalid email or password");
  }
  if (!(await verifyPassword(password, u.password_hash))) {
    await p.query(`UPDATE cc_users SET
                     locked_until = CASE WHEN failed_logins + 1 >= $2 THEN now() + interval '15 minutes' ELSE locked_until END,
                     failed_logins = CASE WHEN failed_logins + 1 >= $2 THEN 0 ELSE failed_logins + 1 END WHERE id = $1`, [u.id, MAX_FAILED]);
    await audit(actor, "auth.login", "FAILURE", u.id, { reason: "bad password" });
    throw new HttpError(401, "INVALID_CREDENTIALS", "Invalid email or password");
  }
  await p.query("UPDATE cc_users SET failed_logins = 0, locked_until = NULL WHERE id = $1", [u.id]);
  if (u.status === "PENDING_MFA") {
    // Password ok but MFA never configured: enrollment is mandatory before any session exists.
    const enroll = await tx((c) => createEnrollment(c, u.id, u.email));
    return { step: "ENROLL", enroll };
  }
  if (await useTrustedDevice(req, u.id)) {
    await audit(actor, "auth.login", "SUCCESS", u.id, { method: "password+trusted_device" });
    const s = await createSession(u.id, req, { trustDevice: false });
    return { step: "DONE", ...s };
  }
  const token = randomToken();
  await p.query(`INSERT INTO cc_auth_challenges (id, user_id, purpose, token_hash, expires_at) VALUES ($1,$2,'MFA_LOGIN',$3, now() + interval '5 minutes')`,
    [uuid(), u.id, sha256(token)]);
  return { step: "MFA", challenge: token };
}

// ------------------------------------------------------------------ trusted devices

async function useTrustedDevice(req: Request, userId: string): Promise<boolean> {
  const token = readCookie(req, deviceCookieName());
  if (!token) return false;
  const p = await db();
  const r = await p.query(`UPDATE cc_trusted_devices SET last_used_at = now()
                           WHERE token_hash = $1 AND user_id = $2 AND revoked_at IS NULL AND expires_at > now() RETURNING id`, [sha256(token), userId]);
  return !!r.rowCount;
}

async function trustDevice(req: Request, userId: string): Promise<string> {
  const token = randomToken();
  const p = await db();
  const ua = (req.headers.get("user-agent") ?? "").slice(0, 200);
  await p.query(`INSERT INTO cc_trusted_devices (id, user_id, token_hash, label, expires_at) VALUES ($1,$2,$3,$4, now() + make_interval(days => $5))`,
    [uuid(), userId, sha256(token), ua || null, DEVICE_TRUST_DAYS]);
  return deviceCookie(token, DEVICE_TRUST_DAYS * 86400);
}

async function hasTrustedDevice(req: Request, userId: string): Promise<boolean> {
  const token = readCookie(req, deviceCookieName());
  if (!token) return false;
  const p = await db();
  return !!(await p.query("SELECT 1 FROM cc_trusted_devices WHERE token_hash = $1 AND user_id = $2 AND revoked_at IS NULL AND expires_at > now()",
    [sha256(token), userId])).rowCount;
}

export async function listDevices(userId: string, req: Request) {
  const p = await db();
  const current = readCookie(req, deviceCookieName());
  return (await p.query(`SELECT id, label, created_at AS "createdAt", last_used_at AS "lastUsedAt", expires_at AS "expiresAt",
                           token_hash = $2 AS current
                         FROM cc_trusted_devices WHERE user_id = $1 AND revoked_at IS NULL AND expires_at > now() ORDER BY last_used_at DESC`,
    [userId, current ? sha256(current) : ""])).rows;
}

export async function revokeDevice(ctx: AuthContext, deviceId: string) {
  const p = await db();
  const r = await p.query("UPDATE cc_trusted_devices SET revoked_at = now() WHERE id = $1 AND user_id = $2 AND revoked_at IS NULL", [deviceId, ctx.user.id]);
  await audit(ctx.actor, "auth.device_revoked", r.rowCount ? "SUCCESS" : "FAILURE", deviceId);
}

/** Ends every session and forgets every trusted device of a user (password/MFA changes, disable, recovery). */
async function signOutEverywhere(q: { query: (sql: string, params: unknown[]) => Promise<unknown> }, userId: string, exceptSessionId?: string) {
  await q.query("UPDATE cc_sessions SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL AND ($2::uuid IS NULL OR id <> $2)", [userId, exceptSessionId ?? null]);
  await q.query("UPDATE cc_trusted_devices SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL", [userId]);
}

/** Second step on a new device: TOTP code or a single-use recovery code. Optionally trusts this browser for 30 days. */
export async function loginMfa(req: Request, challenge: string, code: string, rememberDevice = true) {
  const ip = clientIp(req);
  await rateLimit(`mfa-ip:${ip}`, 30, 900);
  const p = await db();
  const r = await p.query(`SELECT ch.id, ch.attempts, u.id AS user_id, u.email, u.mfa_secret_enc, u.status FROM cc_auth_challenges ch
                           JOIN cc_users u ON u.id = ch.user_id
                           WHERE ch.token_hash = $1 AND ch.purpose = 'MFA_LOGIN' AND ch.used_at IS NULL AND ch.expires_at > now()`, [sha256(challenge)]);
  const ch = r.rows[0];
  if (!ch || ch.status !== "ACTIVE" || ch.attempts >= 5) throw new HttpError(401, "MFA_INVALID", "Sign in again");
  const actor: Actor = { id: ch.user_id, label: ch.email, ip };
  let ok = verifyTotp(open(JSON.parse(ch.mfa_secret_enc), userAad(ch.user_id)), code.trim());
  let usedRecovery = false;
  if (!ok && /^[A-Z2-7]{10}$/i.test(code.trim())) {
    const rc = await p.query("UPDATE cc_recovery_codes SET used_at = now() WHERE user_id = $1 AND code_hash = $2 AND used_at IS NULL RETURNING id",
      [ch.user_id, sha256(code.trim().toUpperCase())]);
    ok = !!rc.rowCount;
    usedRecovery = ok;
  }
  if (!ok) {
    await p.query("UPDATE cc_auth_challenges SET attempts = attempts + 1 WHERE id = $1", [ch.id]);
    await audit(actor, "auth.mfa", "FAILURE", ch.user_id);
    throw new HttpError(401, "MFA_INVALID", "Invalid code");
  }
  await p.query("UPDATE cc_auth_challenges SET used_at = now() WHERE id = $1", [ch.id]);
  await audit(actor, usedRecovery ? "auth.recovery_code_used" : "auth.login", "SUCCESS", ch.user_id, { method: "password+mfa" });
  return createSession(ch.user_id, req, { trustDevice: rememberDevice, remember: rememberDevice });
}

/** New session (cookies[0] is always the session cookie). Optionally also trusts this browser. */
export async function createSession(userId: string, req: Request, opts: { trustDevice?: boolean; remember?: boolean } = {}) {
  const token = randomToken();
  const csrf = randomToken(24);
  const p = await db();
  await p.query(`INSERT INTO cc_sessions (id, user_id, token_hash, csrf_token, ip, user_agent, reauth_at, expires_at, idle_expires_at, remember_device)
                 VALUES ($1,$2,$3,$4,$5,$6, now(), now() + make_interval(days => $7), now() + make_interval(days => $8), $9)`,
    [uuid(), userId, sha256(token), csrf, clientIp(req), (req.headers.get("user-agent") ?? "").slice(0, 300), SESSION_ABSOLUTE_DAYS, SESSION_IDLE_DAYS,
      opts.remember ?? true]);
  const cookies = [sessionCookie(token, SESSION_ABSOLUTE_DAYS * 86400)];
  if (opts.trustDevice) cookies.push(await trustDevice(req, userId));
  return { cookie: cookies[0]!, cookies, csrfToken: csrf };
}

/** State-changing requests must come from this site (Origin header), in addition to SameSite=Strict cookies. */
export function assertSameOrigin(req: Request) {
  const origin = req.headers.get("origin");
  const allowed = new Set([new URL(req.url).origin, ...env.allowedOrigins()]);
  if (!origin || !allowed.has(origin)) throw deny("CSRF", "Cross-origin request refused");
}

export function readCookie(req: Request, name: string): string | null {
  const raw = req.headers.get("cookie") ?? "";
  for (const part of raw.split(";")) {
    const [k, ...v] = part.trim().split("=");
    if (k === name) return v.join("=") || null;
  }
  return null;
}

/**
 * Validates the session cookie against the database and slides the inactivity window. The token that was just
 * replaced by a rotation is still accepted for ROTATION_GRACE_SECONDS (other tabs / in-flight requests).
 */
export async function currentSession(req: Request): Promise<(AuthContext & { rotationDue: boolean; rememberDevice: boolean }) | null> {
  const token = readCookie(req, cookieName());
  if (!token) return null;
  const p = await db();
  const r = await p.query(`UPDATE cc_sessions s SET idle_expires_at = LEAST(s.expires_at, now() + make_interval(days => $2))
                           FROM cc_users u
                           WHERE (s.token_hash = $1 OR (s.prev_token_hash = $1 AND s.prev_valid_until > now()))
                             AND u.id = s.user_id AND s.revoked_at IS NULL AND s.expires_at > now() AND s.idle_expires_at > now() AND u.status = 'ACTIVE'
                           RETURNING s.id, s.user_id, s.csrf_token, s.reauth_at, s.expires_at, s.remember_device,
                                     (s.token_hash = $1 AND s.rotated_at < now() - make_interval(hours => $3)) AS rotation_due,
                                     u.email, u.display_name, u.role, u.status`,
    [sha256(token), SESSION_IDLE_DAYS, ROTATE_AFTER_HOURS]);
  const s = r.rows[0];
  if (!s) return null;
  return {
    user: { id: s.user_id, email: s.email, displayName: s.display_name, role: s.role, status: s.status },
    session: { id: s.id, userId: s.user_id, csrfToken: s.csrf_token, reauthAt: new Date(s.reauth_at), expiresAt: new Date(s.expires_at) },
    actor: { id: s.user_id, label: s.email, ip: clientIp(req) },
    rotationDue: !!s.rotation_due,
    rememberDevice: !!s.remember_device,
  };
}

/**
 * Keep-alive used by the panel (page loads, focus, every few minutes): slides the session, rotates its token when due
 * (the old one stays valid for a short grace period) and re-trusts this browser if it was forgotten — e.g. sessions
 * that existed before trusted devices, or after a password change. Returns cookies to set.
 */
export async function refreshSession(req: Request): Promise<{ ctx: AuthContext; cookies: string[] } | null> {
  const ctx = await currentSession(req);
  if (!ctx) return null;
  const cookies: string[] = [];
  if (ctx.rotationDue) {
    const old = readCookie(req, cookieName())!;
    const next = randomToken();
    const p = await db();
    const r = await p.query(`UPDATE cc_sessions SET prev_token_hash = token_hash, prev_valid_until = now() + make_interval(secs => $3),
                               token_hash = $2, rotated_at = now() WHERE id = $1 AND token_hash = $4 RETURNING expires_at`,
      [ctx.session.id, sha256(next), ROTATION_GRACE_SECONDS, sha256(old)]);
    // A concurrent request may have rotated first: then this one keeps the old token (still valid during the grace period).
    if (r.rowCount) cookies.push(sessionCookie(next, (new Date(r.rows[0].expires_at).getTime() - Date.now()) / 1000));
  }
  if (ctx.rememberDevice && !(await hasTrustedDevice(req, ctx.user.id))) cookies.push(await trustDevice(req, ctx.user.id));
  return { ctx, cookies };
}

/**
 * Authorization for API routes. Mutations require: same-origin (Origin header) AND the session-bound CSRF token in
 * X-CSRF-Token (synchronizer token). Sensitive actions additionally require a recent re-authentication.
 */
export async function requireAuth(req: Request, opts: { role?: Role; mutation?: boolean; reauth?: boolean } = {}): Promise<AuthContext> {
  const ctx = await currentSession(req);
  if (!ctx) throw new HttpError(401, "UNAUTHENTICATED", "Sign in required");
  const mutation = opts.mutation ?? !["GET", "HEAD", "OPTIONS"].includes(req.method);
  if (mutation) {
    assertSameOrigin(req);
    const csrf = req.headers.get("x-csrf-token") ?? "";
    if (!safeEqual(csrf, ctx.session.csrfToken)) throw deny("CSRF", "Missing or invalid CSRF token");
  }
  if (opts.role && RANK[ctx.user.role] < RANK[opts.role]) {
    await audit(ctx.actor, "authz.denied", "DENIED", new URL(req.url).pathname, { required: opts.role });
    throw deny("FORBIDDEN", "Your role does not allow this action");
  }
  if (opts.reauth && Date.now() - ctx.session.reauthAt.getTime() > REAUTH_MINUTES * 60_000) {
    throw new HttpError(403, "REAUTH_REQUIRED", "Confirm your password to continue");
  }
  return ctx;
}

/** Confirms identity for sensitive actions with the password (the session itself already passed MFA on this device). */
export async function reauthenticate(req: Request, ctx: AuthContext, password: string): Promise<void> {
  await rateLimit(`reauth:${ctx.user.id}`, 10, 900);
  const p = await db();
  const u = (await p.query("SELECT password_hash FROM cc_users WHERE id = $1", [ctx.user.id])).rows[0];
  const ok = await verifyPassword(password, u.password_hash);
  if (!ok) { await audit(ctx.actor, "auth.reauth", "FAILURE"); throw new HttpError(401, "REAUTH_FAILED", "Invalid password"); }
  await p.query("UPDATE cc_sessions SET reauth_at = now() WHERE id = $1", [ctx.session.id]);
  await audit(ctx.actor, "auth.reauth", "SUCCESS");
}

export async function logout(req: Request): Promise<void> {
  const ctx = await currentSession(req);
  if (!ctx) return;
  const p = await db();
  await p.query("UPDATE cc_sessions SET revoked_at = now() WHERE id = $1", [ctx.session.id]);
  await audit(ctx.actor, "auth.logout", "SUCCESS");
}

export async function revokeAllSessions(ctx: AuthContext, userId: string): Promise<number> {
  const p = await db();
  const r = await p.query("UPDATE cc_sessions SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL", [userId]);
  await p.query("UPDATE cc_trusted_devices SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL", [userId]);
  await audit(ctx.actor, "auth.sessions_revoked", "SUCCESS", userId, { count: r.rowCount });
  return r.rowCount ?? 0;
}

// ------------------------------------------------------------------ administrators
export async function listUsers() {
  const p = await db();
  return (await p.query(`SELECT id, email, display_name AS "displayName", role, status, mfa_enabled_at AS "mfaEnabledAt", created_at AS "createdAt"
                         FROM cc_users ORDER BY created_at`)).rows;
}

/** Owner-only: creates an expressly authorized administrator with a one-time temporary password; MFA enrollment is forced on first login. */
export async function createAdministrator(ctx: AuthContext, input: { email: string; displayName: string; role: "ADMIN" | "VIEWER" }) {
  if (ctx.user.role !== "OWNER") throw deny();
  const temp = `${randomToken(12)}Aa1!`;
  const id = uuid();
  const p = await db();
  await p.query("INSERT INTO cc_users (id, email, display_name, password_hash, role, status, created_by) VALUES ($1,$2,$3,$4,$5,'PENDING_MFA',$6)",
    [id, input.email.trim().toLowerCase(), input.displayName.trim(), await hashPassword(temp), input.role, ctx.user.id]);
  await audit(ctx.actor, "user.created", "SUCCESS", id, { role: input.role });
  return { id, temporaryPassword: temp };
}

export async function setUserStatus(ctx: AuthContext, userId: string, status: "ACTIVE" | "DISABLED") {
  if (ctx.user.role !== "OWNER" || userId === ctx.user.id) throw deny();
  const p = await db();
  await p.query("UPDATE cc_users SET status = $2, updated_at = now() WHERE id = $1 AND role <> 'OWNER'", [userId, status]);
  if (status === "DISABLED") await signOutEverywhere(p, userId);
  await audit(ctx.actor, status === "DISABLED" ? "user.disabled" : "user.enabled", "SUCCESS", userId);
}

/** Owner-only: device lost. Clears the user's TOTP secret and recovery codes, ends their sessions and forces a new
 *  MFA enrollment at the next login (password still required). Never applies to an OWNER account. */
export async function resetMfa(ctx: AuthContext, userId: string) {
  if (ctx.user.role !== "OWNER" || userId === ctx.user.id) throw deny();
  await tx(async (c) => {
    const r = await c.query(`UPDATE cc_users SET status = 'PENDING_MFA', mfa_secret_enc = NULL, mfa_enabled_at = NULL, updated_at = now()
                             WHERE id = $1 AND role <> 'OWNER' AND status <> 'DISABLED'`, [userId]);
    if (!r.rowCount) throw deny("MFA_RESET_NOT_ALLOWED", "User not found, disabled or owner");
    await c.query("DELETE FROM cc_recovery_codes WHERE user_id = $1", [userId]);
    await signOutEverywhere(c, userId);
  });
  await audit(ctx.actor, "user.mfa_reset", "SUCCESS", userId);
}

export async function changePassword(ctx: AuthContext, current: string, next: string) {
  const p = await db();
  const u = (await p.query("SELECT password_hash FROM cc_users WHERE id = $1", [ctx.user.id])).rows[0];
  if (!(await verifyPassword(current, u.password_hash))) throw new HttpError(401, "INVALID_CREDENTIALS", "Current password is wrong");
  const problem = passwordProblem(next, ctx.user.email);
  if (problem) throw bad(problem);
  await p.query("UPDATE cc_users SET password_hash = $2, updated_at = now() WHERE id = $1", [ctx.user.id, await hashPassword(next)]);
  // Other sessions end and every trusted device is forgotten; this browser is re-trusted by the next keep-alive.
  await signOutEverywhere(p, ctx.user.id, ctx.session.id);
  await audit(ctx.actor, "auth.password_changed", "SUCCESS");
}

// ------------------------------------------------------------------ access recovery (single-use, short-lived tokens)

/** Issues a one-time password-reset token (stored hashed). {@code resetMfa} also forces a new MFA enrollment. */
export async function createPasswordReset(userId: string, opts: { resetMfa: boolean; minutes: number }): Promise<{ token: string; expiresAt: Date }> {
  const token = randomToken();
  const p = await db();
  // Only the newest token per user stays usable.
  await p.query("UPDATE cc_auth_challenges SET used_at = now() WHERE user_id = $1 AND purpose = 'PASSWORD_RESET' AND used_at IS NULL", [userId]);
  const r = await p.query(`INSERT INTO cc_auth_challenges (id, user_id, purpose, token_hash, expires_at, reset_mfa)
                           VALUES ($1,$2,'PASSWORD_RESET',$3, now() + make_interval(mins => $4), $5) RETURNING expires_at`,
    [uuid(), userId, sha256(token), opts.minutes, opts.resetMfa]);
  return { token, expiresAt: new Date(r.rows[0].expires_at) };
}

/** Owner-only: a single-use reset link for another administrator (valid 1 hour). Delivered by the owner out of band. */
export async function adminResetLink(ctx: AuthContext, userId: string) {
  if (ctx.user.role !== "OWNER" || userId === ctx.user.id) throw deny();
  const p = await db();
  const u = (await p.query("SELECT id FROM cc_users WHERE id = $1 AND role <> 'OWNER' AND status <> 'DISABLED'", [userId])).rows[0];
  if (!u) throw deny("RESET_NOT_ALLOWED", "User not found, disabled or owner");
  const t = await createPasswordReset(userId, { resetMfa: false, minutes: 60 });
  await audit(ctx.actor, "user.reset_link_created", "SUCCESS", userId);
  return t;
}

/** Consumes a reset token: new password, account unlocked, everything signed out; optionally MFA re-enrollment. */
export async function resetPassword(req: Request, token: string, newPassword: string) {
  const ip = clientIp(req);
  await rateLimit(`reset-ip:${ip}`, 20, 900);
  return tx(async (c) => {
    const r = await c.query(`SELECT ch.id, ch.user_id, ch.reset_mfa, u.email FROM cc_auth_challenges ch JOIN cc_users u ON u.id = ch.user_id
                             WHERE ch.token_hash = $1 AND ch.purpose = 'PASSWORD_RESET' AND ch.used_at IS NULL AND ch.expires_at > now()
                               AND u.status <> 'DISABLED' FOR UPDATE OF ch`, [sha256(token)]);
    const ch = r.rows[0];
    if (!ch) throw new HttpError(400, "RESET_TOKEN_INVALID", "This link is invalid, was already used or has expired");
    const problem = passwordProblem(newPassword, ch.email);
    if (problem) throw bad(problem);
    await c.query("UPDATE cc_auth_challenges SET used_at = now() WHERE id = $1", [ch.id]);
    await c.query(`UPDATE cc_users SET password_hash = $2, failed_logins = 0, locked_until = NULL, updated_at = now() WHERE id = $1`,
      [ch.user_id, await hashPassword(newPassword)]);
    if (ch.reset_mfa) {
      await c.query("UPDATE cc_users SET status = 'PENDING_MFA', mfa_secret_enc = NULL, mfa_enabled_at = NULL WHERE id = $1", [ch.user_id]);
      await c.query("DELETE FROM cc_recovery_codes WHERE user_id = $1", [ch.user_id]);
    }
    await signOutEverywhere(c, ch.user_id);
    await audit({ id: ch.user_id, label: ch.email, ip }, "auth.password_reset", "SUCCESS", ch.user_id, { resetMfa: ch.reset_mfa }, c);
    return { email: ch.email as string, mfaReset: !!ch.reset_mfa };
  });
}

/** Self-service: replaces the 10 single-use recovery codes (previous ones stop working). Shown once. */
export async function regenerateRecoveryCodes(ctx: AuthContext): Promise<string[]> {
  const codes = Array.from({ length: 10 }, () => base32(randomBytes(7)).slice(0, 10));
  await tx(async (c) => {
    await c.query("DELETE FROM cc_recovery_codes WHERE user_id = $1", [ctx.user.id]);
    for (const code of codes) await c.query("INSERT INTO cc_recovery_codes (id, user_id, code_hash) VALUES ($1,$2,$3)", [uuid(), ctx.user.id, sha256(code)]);
    await audit(ctx.actor, "auth.recovery_codes_regenerated", "SUCCESS", ctx.user.id, {}, c);
  });
  return codes;
}

export async function recoveryCodesLeft(userId: string): Promise<number> {
  const p = await db();
  return Number((await p.query("SELECT count(*) FROM cc_recovery_codes WHERE user_id = $1 AND used_at IS NULL", [userId])).rows[0].count);
}

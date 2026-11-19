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

export const SESSION_ABSOLUTE_HOURS = 12;
export const SESSION_IDLE_MINUTES = 30;
export const REAUTH_MINUTES = 10;
const MAX_FAILED = 8;

export interface User { id: string; email: string; displayName: string; role: Role; status: string }
export interface Session { id: string; userId: string; csrfToken: string; reauthAt: Date; expiresAt: Date }
export interface AuthContext { user: User; session: Session; actor: Actor }

export const cookieName = () => (env.isProduction() ? "__Host-vcc_session" : "vcc_session");

export function sessionCookie(token: string, maxAgeSeconds: number): string {
  const secure = env.isProduction() ? "; Secure" : "";
  return `${cookieName()}=${token}; Path=/; HttpOnly; SameSite=Strict; Max-Age=${maxAgeSeconds}${secure}`;
}
export const clearSessionCookie = () => sessionCookie("", 0);

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
  const session = await createSession(result.userId, req);
  return { ...session, recoveryCodes: result.recoveryCodes };
}

// ------------------------------------------------------------------ login
export async function login(req: Request, email: string, password: string): Promise<{ challenge: string; enroll?: EnrollmentChallenge }> {
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
    return { challenge: "ENROLL", enroll };
  }
  const token = randomToken();
  await p.query(`INSERT INTO cc_auth_challenges (id, user_id, purpose, token_hash, expires_at) VALUES ($1,$2,'MFA_LOGIN',$3, now() + interval '5 minutes')`,
    [uuid(), u.id, sha256(token)]);
  return { challenge: token };
}

/** Second step: TOTP code or a single-use recovery code. */
export async function loginMfa(req: Request, challenge: string, code: string) {
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
  await audit(actor, usedRecovery ? "auth.recovery_code_used" : "auth.login", "SUCCESS", ch.user_id);
  return createSession(ch.user_id, req);
}

export async function createSession(userId: string, req: Request) {
  const token = randomToken();
  const csrf = randomToken(24);
  const p = await db();
  await p.query(`INSERT INTO cc_sessions (id, user_id, token_hash, csrf_token, ip, user_agent, reauth_at, expires_at, idle_expires_at)
                 VALUES ($1,$2,$3,$4,$5,$6, now(), now() + make_interval(hours => $7), now() + make_interval(mins => $8))`,
    [uuid(), userId, sha256(token), csrf, clientIp(req), (req.headers.get("user-agent") ?? "").slice(0, 300), SESSION_ABSOLUTE_HOURS, SESSION_IDLE_MINUTES]);
  return { cookie: sessionCookie(token, SESSION_ABSOLUTE_HOURS * 3600), csrfToken: csrf };
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

/** Validates the session cookie against the database (sliding idle timeout). */
export async function currentSession(req: Request): Promise<AuthContext | null> {
  const token = readCookie(req, cookieName());
  if (!token) return null;
  const p = await db();
  const r = await p.query(`UPDATE cc_sessions s SET idle_expires_at = now() + make_interval(mins => $2)
                           FROM cc_users u WHERE s.token_hash = $1 AND u.id = s.user_id AND s.revoked_at IS NULL AND s.expires_at > now()
                             AND s.idle_expires_at > now() AND u.status = 'ACTIVE'
                           RETURNING s.id, s.user_id, s.csrf_token, s.reauth_at, s.expires_at, u.email, u.display_name, u.role, u.status`,
    [sha256(token), SESSION_IDLE_MINUTES]);
  const s = r.rows[0];
  if (!s) return null;
  return {
    user: { id: s.user_id, email: s.email, displayName: s.display_name, role: s.role, status: s.status },
    session: { id: s.id, userId: s.user_id, csrfToken: s.csrf_token, reauthAt: new Date(s.reauth_at), expiresAt: new Date(s.expires_at) },
    actor: { id: s.user_id, label: s.email, ip: clientIp(req) },
  };
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
    throw new HttpError(403, "REAUTH_REQUIRED", "Confirm your password and code to continue");
  }
  return ctx;
}

export async function reauthenticate(req: Request, ctx: AuthContext, password: string, code: string): Promise<void> {
  await rateLimit(`reauth:${ctx.user.id}`, 10, 900);
  const p = await db();
  const u = (await p.query("SELECT password_hash, mfa_secret_enc FROM cc_users WHERE id = $1", [ctx.user.id])).rows[0];
  const ok = (await verifyPassword(password, u.password_hash)) && verifyTotp(open(JSON.parse(u.mfa_secret_enc), userAad(ctx.user.id)), code);
  if (!ok) { await audit(ctx.actor, "auth.reauth", "FAILURE"); throw new HttpError(401, "REAUTH_FAILED", "Invalid password or code"); }
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
  if (status === "DISABLED") await p.query("UPDATE cc_sessions SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL", [userId]);
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
    await c.query("UPDATE cc_sessions SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL", [userId]);
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
  await p.query("UPDATE cc_sessions SET revoked_at = now() WHERE user_id = $1 AND id <> $2 AND revoked_at IS NULL", [ctx.user.id, ctx.session.id]);
  await audit(ctx.actor, "auth.password_changed", "SUCCESS");
}

import { beforeAll, beforeEach, describe, expect, it } from "vitest";
import { OWNER, bootstrapOwner, code, reauth, req, resetDb, sql, type Client } from "./helpers";

/** Persistent sessions, trusted devices (e-mail + password) and single-use access recovery. */
type R = { GET?: (r: Request) => Promise<Response>; POST?: (r: Request) => Promise<Response> };
const route = (p: string) => import(/* @vite-ignore */ `@/app/api/${p}/route`) as Promise<R>;

/** All cookies a response sets, as name=value pairs (first = session cookie). */
const setCookies = (r: Response) => r.headers.getSetCookie();
const pairs = (r: Response) => setCookies(r).map((c) => c.split(";")[0]!);
const jar = (...cookies: (string | null | undefined)[]) => cookies.filter(Boolean).join("; ");
const named = (r: Response, name: string) => pairs(r).find((p) => p.startsWith(`${name}=`)) ?? null;

async function login(email: string, password: string, device?: string | null) {
  return (await route("auth/login")).POST!(req("POST", "/api/auth/login", { body: { email, password }, headers: device ? { cookie: device } : {} }));
}
async function mfa(challenge: string, secret: string, rememberDevice?: boolean, step = 0) {
  return (await route("auth/mfa")).POST!(req("POST", "/api/auth/mfa", { body: { challenge, code: code(secret, step), rememberDevice } }));
}
const me = async (cookie: string) => (await route("auth/me")).GET!(req("GET", "/api/auth/me", { client: { cookie, csrf: null } }));

describe("persistent sessions and trusted devices", () => {
  let owner: Awaited<ReturnType<typeof bootstrapOwner>>;
  let device: string;

  beforeAll(async () => { await resetDb(); owner = await bootstrapOwner(); });
  beforeEach(async () => { await sql("DELETE FROM cc_rate_limits"); }); // many sign-ins on purpose; the limiter has its own test

  it("first sign-in on a device asks for the second factor once and trusts the device for 30 days", async () => {
    const s = await (await login(OWNER.email, OWNER.password)).json();
    expect(s.step).toBe("MFA");
    const r = await mfa(s.challenge, owner.secret, true, 1);
    expect(r.status).toBe(200);
    const cookies = setCookies(r);
    expect(cookies[0]).toMatch(/^vcc_session=.+; Path=\/; HttpOnly; SameSite=Lax; Max-Age=2592000$/);
    expect(cookies[1]).toMatch(/^vcc_device=.+; Path=\/; HttpOnly; SameSite=Lax; Max-Age=2592000$/);
    device = named(r, "vcc_device")!;
    const row = (await sql("SELECT expires_at - created_at AS life, idle_expires_at > now() + interval '6 days' AS idle FROM cc_sessions ORDER BY created_at DESC LIMIT 1"))[0]!;
    expect(row.life.days).toBe(30);
    expect(row.idle).toBe(true);
  });

  it("on the trusted device, e-mail + password is enough (no code)", async () => {
    const r = await login(OWNER.email, OWNER.password, device);
    const body = await r.json();
    expect(body.step).toBe("DONE");
    expect(body.csrfToken).toBeTruthy();
    expect(named(r, "vcc_session")).toBeTruthy();
    expect((await me(named(r, "vcc_session")!)).status).toBe(200);
    // still the password is required
    expect((await login(OWNER.email, "wrong-password-0000000", device)).status).toBe(401);
    // another browser (no device cookie) still needs the code
    expect((await (await login(OWNER.email, OWNER.password)).json()).step).toBe("MFA");
    const a = await sql("SELECT detail->>'method' AS m FROM cc_audit_events WHERE action = 'auth.login' AND outcome = 'SUCCESS' ORDER BY id DESC LIMIT 1");
    expect(a[0]!.m).toBe("password+trusted_device");
  });

  it("a device cookie of another user does not skip the code", async () => {
    const users = await route("users");
    await reauth(owner);
    const created = await (await users.POST!(req("POST", "/api/users", { client: owner, body: { email: "ana@example.com", displayName: "Ana", role: "ADMIN" } }))).json();
    const step = await (await login("ana@example.com", created.temporaryPassword, device)).json();
    expect(step.step).toBe("ENROLL"); // MFA not set up yet → enrollment, never a session
  });

  it("unchecking 'remember this device' creates no trust", async () => {
    const s = await (await login(OWNER.email, OWNER.password)).json();
    const r = await mfa(s.challenge, owner.secret, false, 2);
    expect(named(r, "vcc_device")).toBeNull();
    const refreshed = await me(named(r, "vcc_session")!);
    expect(named(refreshed, "vcc_device")).toBeNull();
  });

  it("refreshes, slides inactivity and rotates the token with a grace period (no logout from parallel tabs)", async () => {
    const login1 = await login(OWNER.email, OWNER.password, device);
    const oldCookie = named(login1, "vcc_session")!;
    await sql("UPDATE cc_sessions SET rotated_at = now() - interval '13 hours', idle_expires_at = now() + interval '1 hour' WHERE revoked_at IS NULL");
    const [a, b] = await Promise.all([me(jar(oldCookie, device)), me(jar(oldCookie, device))]); // two tabs at once
    expect(a.status).toBe(200);
    expect(b.status).toBe(200);
    const rotated = [a, b].map((r) => named(r, "vcc_session")).filter(Boolean);
    expect(rotated.length).toBeGreaterThanOrEqual(1);
    const newCookie = rotated[0]!;
    expect(newCookie).not.toBe(oldCookie);
    expect((await me(oldCookie)).status).toBe(200); // old token still valid during the grace period
    expect((await me(newCookie)).status).toBe(200);
    const idle = (await sql("SELECT bool_and(idle_expires_at > now() + interval '6 days') AS ok FROM cc_sessions WHERE rotated_at > now() - interval '1 minute'"))[0]!;
    expect(idle.ok).toBe(true); // inactivity window of the rotated session slid back to 7 days
    await sql("UPDATE cc_sessions SET prev_valid_until = now() - interval '1 second' WHERE prev_token_hash IS NOT NULL");
    expect((await me(oldCookie)).status).toBe(401);
    expect((await me(newCookie)).status).toBe(200);
  });

  it("expires after 7 days without use and after 30 days in any case", async () => {
    const c = named(await login(OWNER.email, OWNER.password, device), "vcc_session")!;
    await sql("UPDATE cc_sessions SET idle_expires_at = now() - interval '1 second' WHERE revoked_at IS NULL");
    expect((await me(c)).status).toBe(401);
    const c2 = named(await login(OWNER.email, OWNER.password, device), "vcc_session")!;
    await sql("UPDATE cc_sessions SET expires_at = now() - interval '1 second' WHERE revoked_at IS NULL");
    expect((await me(c2)).status).toBe(401);
  });

  it("logout revokes the session but keeps the device trusted (next sign-in: e-mail + password)", async () => {
    const r = await login(OWNER.email, OWNER.password, device);
    const c: Client = { cookie: named(r, "vcc_session"), csrf: (await r.json()).csrfToken };
    await (await route("auth/logout")).POST!(req("POST", "/api/auth/logout", { client: c }));
    expect((await me(c.cookie!)).status).toBe(401);
    expect((await (await login(OWNER.email, OWNER.password, device)).json()).step).toBe("DONE");
  });

  it("re-authentication for sensitive actions needs only the password", async () => {
    const r = await login(OWNER.email, OWNER.password, device);
    const c: Client = { cookie: named(r, "vcc_session"), csrf: (await r.json()).csrfToken };
    await sql("UPDATE cc_sessions SET reauth_at = now() - interval '1 hour'");
    const ra = await route("auth/reauth");
    expect((await ra.POST!(req("POST", "/api/auth/reauth", { client: c, body: { password: "wrong" } }))).status).toBe(401);
    expect((await ra.POST!(req("POST", "/api/auth/reauth", { client: c, body: { password: OWNER.password } }))).status).toBe(200);
  });

  it("device list, forgetting a device and signing out everywhere", async () => {
    const r = await login(OWNER.email, OWNER.password, device);
    const c: Client = { cookie: jar(named(r, "vcc_session"), device), csrf: (await r.json()).csrfToken };
    const list = await (await (await route("auth/devices")).GET!(req("GET", "/api/auth/devices", { client: c }))).json();
    expect(list.some((d: { current: boolean }) => d.current)).toBe(true);
    await (await route("auth/reauth")).POST!(req("POST", "/api/auth/reauth", { client: c, body: { password: OWNER.password } }));
    const out = await (await route("auth/devices")).POST!(req("POST", "/api/auth/devices", { client: c, body: { action: "sign_out_everywhere" } }));
    expect(out.status).toBe(200);
    expect((await me(named(r, "vcc_session")!)).status).toBe(401);
    expect((await (await login(OWNER.email, OWNER.password, device)).json()).step).toBe("MFA"); // device forgotten
  });

  it("a password change ends other sessions and forgets devices; the current browser is re-trusted", async () => {
    const s = await (await login(OWNER.email, OWNER.password)).json();
    const r = await mfa(s.challenge, owner.secret, true, 1);
    const dev = named(r, "vcc_device")!;
    const c: Client = { cookie: jar(named(r, "vcc_session"), dev), csrf: (await r.json()).csrfToken };
    await (await route("auth/reauth")).POST!(req("POST", "/api/auth/reauth", { client: c, body: { password: OWNER.password } }));
    const NEW = "Another-Correct-Horse-88";
    expect((await (await route("auth/password")).POST!(req("POST", "/api/auth/password", { client: c, body: { currentPassword: OWNER.password, newPassword: NEW } }))).status).toBe(200);
    const keep = await me(c.cookie!);
    expect(keep.status).toBe(200); // still signed in here
    const newDev = named(keep, "vcc_device");
    expect(newDev).toBeTruthy(); // re-trusted by the keep-alive
    expect((await (await login(OWNER.email, NEW, newDev)).json()).step).toBe("DONE");
    // restore the password for the following tests
    const c2: Client = { cookie: jar(named(await login(OWNER.email, NEW, newDev), "vcc_session"), newDev), csrf: null };
    const m = await (await me(c2.cookie!)).json();
    c2.csrf = m.csrfToken;
    await (await route("auth/reauth")).POST!(req("POST", "/api/auth/reauth", { client: c2, body: { password: NEW } }));
    await (await route("auth/password")).POST!(req("POST", "/api/auth/password", { client: c2, body: { currentPassword: NEW, newPassword: OWNER.password } }));
    device = newDev!;
  });
});

describe("access recovery with single-use tokens", () => {
  let owner: Awaited<ReturnType<typeof bootstrapOwner>>;
  let ana: { id: string; secret: string };

  beforeAll(async () => {
    await resetDb();
    owner = await bootstrapOwner();
    await reauth(owner);
    const created = await (await (await route("users")).POST!(req("POST", "/api/users", { client: owner, body: { email: "ana@example.com", displayName: "Ana", role: "ADMIN" } }))).json();
    const step = await (await login("ana@example.com", created.temporaryPassword)).json();
    await (await route("auth/enroll")).POST!(req("POST", "/api/auth/enroll", { body: { enrollToken: step.enroll.enrollToken, code: code(step.enroll.secret) } }));
    ana = { id: created.id, secret: step.enroll.secret };
  });
  beforeEach(async () => { await sql("DELETE FROM cc_rate_limits"); });

  const resetLink = async () => {
    await reauth(owner);
    const r = await (await import("@/app/api/users/[id]/route")).POST(req("POST", `/api/users/${ana.id}`, { client: owner, body: { action: "reset_link" } }),
      { params: Promise.resolve({ id: ana.id }) });
    return (await r.json()) as { url: string; expiresAt: string };
  };
  const reset = async (token: string, newPassword: string) => (await route("auth/reset")).POST!(req("POST", "/api/auth/reset", { body: { token, newPassword } }));

  it("owner creates a one-hour, single-use reset link; the token is in the fragment and stored only as a hash", async () => {
    const { url, expiresAt } = await resetLink();
    expect(url).toMatch(/^https:\/\/control\.test\/reset#[A-Za-z0-9_-]{40,}$/);
    expect(new Date(expiresAt).getTime() - Date.now()).toBeGreaterThan(59 * 60_000);
    const token = url.split("#")[1]!;
    expect(JSON.stringify(await sql("SELECT * FROM cc_auth_challenges"))).not.toContain(token);
    expect((await reset(token, "Blue-Lagoon-Sunrise-2026!")).status).toBe(200);
    expect((await reset(token, "Green-Valley-Breeze-2026!")).status).toBe(400); // single use
    expect((await (await login("ana@example.com", "Blue-Lagoon-Sunrise-2026!")).json()).step).toBe("MFA"); // MFA kept
  });

  it("a newer link invalidates the previous one; expired links fail", async () => {
    const first = (await resetLink()).url.split("#")[1]!;
    const second = (await resetLink()).url.split("#")[1]!;
    expect((await reset(first, "Silver-River-Stone-2026!")).status).toBe(400);
    await sql("UPDATE cc_auth_challenges SET expires_at = now() - interval '1 second' WHERE purpose = 'PASSWORD_RESET' AND used_at IS NULL");
    expect((await reset(second, "Silver-River-Stone-2026!")).status).toBe(400);
  });

  it("only the owner can create links, and not for owners", async () => {
    const r = await (await import("@/app/api/users/[id]/route")).POST(req("POST", `/api/users/${ana.id}`, { client: owner, body: { action: "reset_link" } }),
      { params: Promise.resolve({ id: ana.id }) });
    expect(r.status).toBe(200);
    const ownerId = (await sql("SELECT id FROM cc_users WHERE role = 'OWNER'"))[0]!.id;
    const self = await (await import("@/app/api/users/[id]/route")).POST(req("POST", `/api/users/${ownerId}`, { client: owner, body: { action: "reset_link" } }),
      { params: Promise.resolve({ id: ownerId }) });
    expect(self.status).toBe(403);
  });

  it("owner emergency recovery with the installation key resets password and MFA and signs out everywhere", async () => {
    const rec = await route("auth/recover");
    expect((await rec.POST!(req("POST", "/api/auth/recover", { body: { email: OWNER.email, key: "x".repeat(40) } }))).status).toBe(401);
    const r = await rec.POST!(req("POST", "/api/auth/recover", { body: { email: OWNER.email, key: process.env.CONTROL_SETUP_KEY } }));
    expect(r.status).toBe(200);
    const { token } = await r.json();
    const done = await (await reset(token, "Quiet-Harbor-Lantern-77!")).json();
    expect(done).toMatchObject({ mfaReset: true });
    expect((await me(owner.cookie!)).status).toBe(401);
    const step = await (await login(OWNER.email, "Quiet-Harbor-Lantern-77!")).json();
    expect(step.step).toBe("ENROLL"); // new authenticator
  });

  it("recovery codes can be regenerated; old ones stop working", async () => {
    const s = await (await login("ana@example.com", "Blue-Lagoon-Sunrise-2026!")).json();
    const r = await mfa(s.challenge, ana.secret, true, 1);
    const c: Client = { cookie: jar(named(r, "vcc_session"), named(r, "vcc_device")), csrf: (await r.json()).csrfToken };
    await (await route("auth/reauth")).POST!(req("POST", "/api/auth/reauth", { client: c, body: { password: "Blue-Lagoon-Sunrise-2026!" } }));
    const { recoveryCodes } = await (await (await route("auth/recovery-codes")).POST!(req("POST", "/api/auth/recovery-codes", { client: c }))).json();
    expect(recoveryCodes).toHaveLength(10);
    expect((await (await me(c.cookie!)).json()).recoveryCodesLeft).toBe(10);
    const s2 = await (await login("ana@example.com", "Blue-Lagoon-Sunrise-2026!")).json();
    expect((await (await route("auth/mfa")).POST!(req("POST", "/api/auth/mfa", { body: { challenge: s2.challenge, code: recoveryCodes[0] } }))).status).toBe(200);
  });
});

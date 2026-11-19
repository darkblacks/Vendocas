import { beforeAll, describe, expect, it } from "vitest";
import { OWNER, bootstrapOwner, code, cookieFrom, reauth, req, resetDb, sql } from "./helpers";

const route = <T>(p: string) => import(/* @vite-ignore */ `@/app/api/${p}/route`) as Promise<T>;
type H = { GET?: (r: Request, c?: unknown) => Promise<Response>; POST?: (r: Request, c?: unknown) => Promise<Response>; PUT?: (r: Request, c?: unknown) => Promise<Response> };

describe("bootstrap and authentication", () => {
  let owner: Awaited<ReturnType<typeof bootstrapOwner>>;

  beforeAll(async () => { await resetDb(); });

  it("rejects a wrong installation key or a different e-mail (same answer for both)", async () => {
    const { setupSession } = await import("./helpers");
    const wrongKey = await setupSession(OWNER.email, "x".repeat(40));
    expect(wrongKey.res.status).toBe(401);
    expect(wrongKey.cookie).toBeNull();
    const wrongEmail = await setupSession("intruder@example.com");
    expect(wrongEmail.res.status).toBe(401);
    expect((await wrongEmail.res.json()).code).toBe("SETUP_CREDENTIALS_INVALID");
    // owner creation without a setup session is refused
    const owner = await import("@/app/api/bootstrap/owner/route");
    const r = await owner.POST(req("POST", "/api/bootstrap/owner", { body: { displayName: "Someone", password: OWNER.password } }));
    expect((await r.json()).code).toBe("SETUP_SESSION_REQUIRED");
  });

  it("creates the owner, forces MFA enrollment and then locks bootstrap", async () => {
    owner = await bootstrapOwner();
    expect(owner.recoveryCodes).toHaveLength(10);
    const { setupSession } = await import("./helpers");
    const again = await setupSession();
    expect(again.res.status).toBe(409);
    expect((await again.res.json()).code).toBe("INSTALLATION_COMPLETE");
    const users = await sql("SELECT role, status, mfa_enabled_at FROM cc_users");
    expect(users).toHaveLength(1);
    expect(users[0]).toMatchObject({ role: "OWNER", status: "ACTIVE" });
    const stored = await sql("SELECT mfa_secret_enc FROM cc_users");
    expect(stored[0]!.mfa_secret_enc).not.toContain(owner.secret);
  });

  it("logs in with password + TOTP, rejects bad password and bad code", async () => {
    const login = await route<H>("auth/login");
    const mfa = await route<H>("auth/mfa");
    expect((await login.POST!(req("POST", "/api/auth/login", { body: { email: OWNER.email, password: "wrong-password-123456" } }))).status).toBe(401);
    expect((await login.POST!(req("POST", "/api/auth/login", { body: { email: "nobody@example.com", password: "wrong-password-123456" } }))).status).toBe(401);
    const step = await (await login.POST!(req("POST", "/api/auth/login", { body: { email: OWNER.email, password: OWNER.password } }))).json();
    expect(step.step).toBe("MFA");
    expect((await mfa.POST!(req("POST", "/api/auth/mfa", { body: { challenge: step.challenge, code: "000000" } }))).status).toBe(401);
    const ok = await mfa.POST!(req("POST", "/api/auth/mfa", { body: { challenge: step.challenge, code: code(owner.secret) } }));
    expect(ok.status).toBe(200);
    const cookie = cookieFrom(ok)!;
    expect(ok.headers.get("set-cookie")).toMatch(/HttpOnly; SameSite=Strict/);
    const me = await (await route<H>("auth/me")).GET!(req("GET", "/api/auth/me", { client: { cookie, csrf: null } }));
    expect((await me.json()).user.email).toBe(OWNER.email);
    // challenge cannot be reused
    expect((await mfa.POST!(req("POST", "/api/auth/mfa", { body: { challenge: step.challenge, code: code(owner.secret) } }))).status).toBe(401);
  });

  it("accepts a recovery code exactly once", async () => {
    const login = await route<H>("auth/login");
    const mfa = await route<H>("auth/mfa");
    const rc = owner.recoveryCodes[0]!;
    const s1 = await (await login.POST!(req("POST", "/api/auth/login", { body: { email: OWNER.email, password: OWNER.password } }))).json();
    expect((await mfa.POST!(req("POST", "/api/auth/mfa", { body: { challenge: s1.challenge, code: rc } }))).status).toBe(200);
    const s2 = await (await login.POST!(req("POST", "/api/auth/login", { body: { email: OWNER.email, password: OWNER.password } }))).json();
    expect((await mfa.POST!(req("POST", "/api/auth/mfa", { body: { challenge: s2.challenge, code: rc } }))).status).toBe(401);
  });

  it("refuses unauthenticated access and cross-site mutations (CSRF)", async () => {
    const vault = await route<H>("vault");
    expect((await vault.GET!(req("GET", "/api/vault"))).status).toBe(401);
    const start = await route<H>("codespaces/start");
    const noCsrf = await start.POST!(req("POST", "/api/codespaces/start", { client: owner, csrf: null }));
    expect(noCsrf.status).toBe(403);
    const foreign = await start.POST!(req("POST", "/api/codespaces/start", { client: owner, origin: "https://evil.example" }));
    expect((await foreign.json()).code).toBe("CSRF");
  });

  it("requires recent re-authentication for sensitive actions", async () => {
    await sql("UPDATE cc_sessions SET reauth_at = now() - interval '1 hour'");
    const vault = await route<H>("vault/[provider]");
    const ctx = { params: Promise.resolve({ provider: "STRIPE" }) };
    const body = { environment: "TEST", values: { secretKey: "sk_test_abcdefghijk", webhookSecret: "whsec_abcdefghijk" } };
    const denied = await vault.PUT!(req("PUT", "/api/vault/STRIPE", { client: owner, body }), ctx);
    expect((await denied.json()).code).toBe("REAUTH_REQUIRED");
    await reauth(owner);
    expect((await vault.PUT!(req("PUT", "/api/vault/STRIPE", { client: owner, body }), ctx)).status).toBe(200);
  });

  it("creates an administrator who must enroll MFA, and enforces roles", async () => {
    await reauth(owner);
    const users = await route<H>("users");
    const created = await (await users.POST!(req("POST", "/api/users", { client: owner, body: { email: "viewer@example.com", displayName: "Viewer", role: "VIEWER" } }))).json();
    const login = await route<H>("auth/login");
    const step = await (await login.POST!(req("POST", "/api/auth/login", { body: { email: "viewer@example.com", password: created.temporaryPassword } }))).json();
    expect(step.step).toBe("ENROLL");
    const enroll = await route<H>("auth/enroll");
    const r = await enroll.POST!(req("POST", "/api/auth/enroll", { body: { enrollToken: step.enroll.enrollToken, code: code(step.enroll.secret) } }));
    const viewer = { cookie: cookieFrom(r), csrf: (await r.json()).csrfToken };
    // VIEWER cannot read the vault, create admins, or start the codespace
    expect((await (await route<H>("vault")).GET!(req("GET", "/api/vault", { client: viewer }))).status).toBe(403);
    expect((await users.POST!(req("POST", "/api/users", { client: viewer, body: { email: "x@example.com", displayName: "X", role: "ADMIN" } }))).status).toBe(403);
    expect((await (await route<H>("codespaces/start")).POST!(req("POST", "/api/codespaces/start", { client: viewer }))).status).toBe(403);
    // but can read the dashboard data
    expect((await (await route<H>("sessions")).GET!(req("GET", "/api/sessions", { client: viewer }))).status).toBe(200);
    const denials = await sql("SELECT count(*)::int AS n FROM cc_audit_events WHERE outcome = 'DENIED' AND action = 'authz.denied'");
    expect(denials[0]!.n).toBeGreaterThanOrEqual(3);
  });

  it("invalidates the session on logout and on idle expiry", async () => {
    const login = await route<H>("auth/login");
    const mfa = await route<H>("auth/mfa");
    const step = await (await login.POST!(req("POST", "/api/auth/login", { body: { email: OWNER.email, password: OWNER.password } }))).json();
    const r = await mfa.POST!(req("POST", "/api/auth/mfa", { body: { challenge: step.challenge, code: code(owner.secret, 1) } }));
    const c = { cookie: cookieFrom(r), csrf: (await r.json()).csrfToken };
    const me = await route<H>("auth/me");
    expect((await me.GET!(req("GET", "/api/auth/me", { client: c }))).status).toBe(200);
    await (await route<H>("auth/logout")).POST!(req("POST", "/api/auth/logout", { client: c }));
    expect((await me.GET!(req("GET", "/api/auth/me", { client: c }))).status).toBe(401);
    await sql("UPDATE cc_sessions SET idle_expires_at = now() - interval '1 second' WHERE revoked_at IS NULL");
    expect((await me.GET!(req("GET", "/api/auth/me", { client: owner }))).status).toBe(401);
  });

  it("locks the account after repeated failures", async () => {
    await sql("DELETE FROM cc_rate_limits");
    const login = await route<H>("auth/login");
    for (let i = 0; i < 8; i++) await login.POST!(req("POST", "/api/auth/login", { body: { email: "viewer@example.com", password: "definitely-wrong-pass" }, headers: { "x-nf-client-connection-ip": `198.51.100.${i}` } }));
    const u = await sql("SELECT locked_until FROM cc_users WHERE email = 'viewer@example.com'");
    expect(u[0]!.locked_until).not.toBeNull();
  });
});

describe("MFA reset by the owner", () => {
  it("forces the user to enroll a new authenticator and ends their sessions", async () => {
    await resetDb();
    const { bootstrapOwner: boot, reauth: re, req: r, code: totp, cookieFrom: ck, sql: q } = await import("./helpers");
    const owner = await boot();
    await re(owner);
    const users = await import("@/app/api/users/route");
    const created = await (await users.POST(r("POST", "/api/users", { client: owner, body: { email: "admin2@example.com", displayName: "A2", role: "ADMIN" } }))).json();
    const login = await import("@/app/api/auth/login/route");
    const enroll = await import("@/app/api/auth/enroll/route");
    const s1 = await (await login.POST(r("POST", "/api/auth/login", { body: { email: "admin2@example.com", password: created.temporaryPassword } }))).json();
    const e1 = await enroll.POST(r("POST", "/api/auth/enroll", { body: { enrollToken: s1.enroll.enrollToken, code: totp(s1.enroll.secret) } }));
    const admin = { cookie: ck(e1), csrf: (await e1.json()).csrfToken };
    const byId = await import("@/app/api/users/[id]/route");
    const ctx = { params: Promise.resolve({ id: created.id }) };
    // an ADMIN cannot reset anyone (owner only)
    expect((await byId.POST(r("POST", `/api/users/${created.id}`, { client: admin, body: { action: "reset_mfa" } }), ctx)).status).toBe(403);
    expect((await byId.POST(r("POST", `/api/users/${created.id}`, { client: owner, body: { action: "reset_mfa" } }), ctx)).status).toBe(200);
    expect((await (await import("@/app/api/auth/me/route")).GET(r("GET", "/api/auth/me", { client: admin }))).status).toBe(401);
    const s2 = await (await login.POST(r("POST", "/api/auth/login", { body: { email: "admin2@example.com", password: created.temporaryPassword } }))).json();
    expect(s2.step).toBe("ENROLL");
    expect((await q("SELECT count(*)::int AS n FROM cc_recovery_codes WHERE user_id = $1", [created.id]))[0]!.n).toBe(0);
    // the owner's own MFA cannot be reset this way
    const ownerId = (await q("SELECT id FROM cc_users WHERE role = 'OWNER'"))[0]!.id;
    await re(owner);
    expect((await byId.POST(r("POST", `/api/users/${ownerId}`, { client: owner, body: { action: "reset_mfa" } }), { params: Promise.resolve({ id: ownerId }) })).status).toBe(403);
  });
});

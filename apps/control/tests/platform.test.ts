import { beforeAll, beforeEach, describe, expect, it } from "vitest";
import { FakeGitHub, bootstrapOwner, cookieFrom, code, reauth, req, resetDb, sql } from "./helpers";

/** Control Center ↔ Vendocas API for environment / Stripe / fiscal switches (fake homologation and production APIs). */
const STATUS_TOKEN = "status-token-0123456789abcdefghijklmnop";
const ADMIN_TOKEN = "admin-token-0123456789abcdefghijklmnopq";

function fakeApi(host: string, writable = true) {
  const state = {
    settings: { environment: "HOMOLOGATION", stripeMode: "TEST", fiscalAutomation: false, fiscalProvider: "NONE", version: 1, updatedBy: null as string | null, updatedAt: new Date().toISOString() },
    puts: [] as { headers: Record<string, string>; body: unknown }[],
  };
  const status = () => ({
    settings: state.settings, stripeKeyMode: "LIVE", stripeConfigured: true, stripeWebhookConfigured: true, stripeModeMatches: state.settings.stripeMode === "LIVE",
    focusConfigured: false, focusEnvironment: "HOMOLOGATION", fiscalMode: state.settings.fiscalAutomation ? "AUTOMATIC_WITHOUT_PROVIDER" : "MANUAL",
    fiscalDocuments: { AWAITING_MANUAL: 2 }, services: [{ key: "stripe", label: "Pagamentos (Stripe)", state: "ACTIVE", detail: null }], warnings: [],
  });
  const handler = async (url: URL, init?: RequestInit): Promise<Response | null> => {
    if (url.host !== host) return null;
    const h = Object.fromEntries(Object.entries((init?.headers ?? {}) as Record<string, string>).map(([k, v]) => [k.toLowerCase(), v]));
    if (url.pathname === "/internal/status") {
      if (h["x-control-token"] !== STATUS_TOKEN) return new Response(null, { status: 401 });
      return Response.json({ status: "UP", database: { up: true }, redis: { up: true }, workers: { enabled: true }, recentErrors: [], platform: status(), settingsWritable: writable });
    }
    if (url.pathname === "/internal/platform-settings" && init?.method === "PUT") {
      if (!writable) return new Response(null, { status: 404 });
      if (h["x-control-admin-token"] !== ADMIN_TOKEN) return new Response(null, { status: 401 });
      const body = JSON.parse(String(init.body));
      state.puts.push({ headers: h, body });
      if (body.expectedVersion && body.expectedVersion !== state.settings.version) return Response.json({ code: "CONFLICT" }, { status: 409 });
      Object.assign(state.settings, Object.fromEntries(Object.entries(body).filter(([k]) => k !== "expectedVersion")));
      state.settings.version++; state.settings.updatedBy = `control-center:${h["x-control-actor"]}`;
      return Response.json(status());
    }
    return new Response(null, { status: 404 });
  };
  return { state, handler };
}

describe("payments & fiscal settings through the Control Center", () => {
  let owner: Awaited<ReturnType<typeof bootstrapOwner>>;
  const homolog = fakeApi("api-homolog.test");
  const prod = fakeApi("api-prod.test", false);

  beforeAll(async () => {
    await resetDb();
    owner = await bootstrapOwner();
    const gh = new FakeGitHub();
    gh.install(async (url, init) => (await homolog.handler(url, init)) ?? (await prod.handler(url, init)));
    await reauth(owner);
    const v = await import("@/app/api/vault/[provider]/route");
    const save = (env: string, values: Record<string, string>) =>
      v.PUT(req("PUT", "/api/vault/VENDOCAS_API", { client: owner, body: { environment: env, values } }), { params: Promise.resolve({ provider: "VENDOCAS_API" }) });
    expect((await save("TEST", { baseUrl: "https://api-homolog.test", statusToken: STATUS_TOKEN, adminToken: ADMIN_TOKEN })).status).toBe(200);
    expect((await save("LIVE", { baseUrl: "https://api-prod.test", statusToken: STATUS_TOKEN })).status).toBe(200);
  });
  beforeEach(async () => { await reauth(owner); });

  it("shows both APIs with their services; tokens never reach the browser", async () => {
    const r = await (await import("@/app/api/platform/route")).GET(req("GET", "/api/platform", { client: owner }));
    const text = await r.text();
    expect(text).not.toContain(STATUS_TOKEN);
    expect(text).not.toContain(ADMIN_TOKEN);
    const views = JSON.parse(text);
    expect(views.map((v: { target: string }) => v.target)).toEqual(["TEST", "LIVE"]);
    expect(views[0]).toMatchObject({ configured: true, reachable: true, writable: true, platform: { fiscalMode: "MANUAL", fiscalDocuments: { AWAITING_MANUAL: 2 } } });
    expect(views[1]).toMatchObject({ configured: true, reachable: true, writable: false, adminTokenStored: false });
  });

  it("changes the switches independently (homologation + Stripe LIVE + fiscal off), with the acting owner audited", async () => {
    const route = await import("@/app/api/platform/route");
    const r = await route.PUT(req("PUT", "/api/platform", { client: owner, body: { target: "TEST", stripeMode: "LIVE", environment: "HOMOLOGATION", fiscalAutomation: false, expectedVersion: 1 } }));
    expect(r.status).toBe(200);
    expect((await r.json()).settings).toMatchObject({ environment: "HOMOLOGATION", stripeMode: "LIVE", fiscalAutomation: false });
    const put = homolog.state.puts.at(-1)!;
    expect(put.headers["x-control-admin-token"]).toBe(ADMIN_TOKEN); // server-to-server only
    expect(put.headers["x-control-actor"]).toBe("owner@example.com");
    const a = await sql("SELECT outcome, target, detail::text AS d FROM cc_audit_events WHERE action = 'platform.settings_update'");
    expect(a.at(-1)).toMatchObject({ outcome: "SUCCESS", target: "TEST" });
    expect(JSON.stringify(a)).not.toContain(ADMIN_TOKEN);
  });

  it("reports version conflicts instead of overwriting", async () => {
    const route = await import("@/app/api/platform/route");
    const r = await route.PUT(req("PUT", "/api/platform", { client: owner, body: { target: "TEST", fiscalAutomation: true, expectedVersion: 1 } }));
    expect(r.status).toBe(409);
    expect((await r.json()).code).toBe("SETTINGS_CONFLICT");
  });

  it("is read-only without an admin token, and only the OWNER with recent re-authentication may change", async () => {
    const route = await import("@/app/api/platform/route");
    const noToken = await route.PUT(req("PUT", "/api/platform", { client: owner, body: { target: "LIVE", stripeMode: "LIVE" } }));
    expect((await noToken.json()).code).toBe("ADMIN_TOKEN_NOT_CONFIGURED");
    expect(prod.state.puts).toHaveLength(0);
    await sql("UPDATE cc_sessions SET reauth_at = now() - interval '1 hour'");
    expect((await (await route.PUT(req("PUT", "/api/platform", { client: owner, body: { target: "TEST", stripeMode: "TEST" } }))).json()).code).toBe("REAUTH_REQUIRED");
    // an ADMIN may read but not change
    await reauth(owner);
    const users = await import("@/app/api/users/route");
    const created = await (await users.POST(req("POST", "/api/users", { client: owner, body: { email: "ops@example.com", displayName: "Ops", role: "ADMIN" } }))).json();
    const login = await import("@/app/api/auth/login/route");
    const step = await (await login.POST(req("POST", "/api/auth/login", { body: { email: "ops@example.com", password: created.temporaryPassword } }))).json();
    const e = await (await import("@/app/api/auth/enroll/route")).POST(req("POST", "/api/auth/enroll", { body: { enrollToken: step.enroll.enrollToken, code: code(step.enroll.secret) } }));
    const admin = { cookie: cookieFrom(e), csrf: (await e.json()).csrfToken };
    expect((await route.GET(req("GET", "/api/platform", { client: admin }))).status).toBe(200);
    expect((await route.PUT(req("PUT", "/api/platform", { client: admin, body: { target: "TEST", stripeMode: "TEST" } }))).status).toBe(403);
  });

  it("validates the request", async () => {
    const route = await import("@/app/api/platform/route");
    expect((await route.PUT(req("PUT", "/api/platform", { client: owner, body: { target: "STAGING", stripeMode: "LIVE" } }))).status).toBe(400);
    expect((await route.PUT(req("PUT", "/api/platform", { client: owner, body: { target: "TEST", fiscalProvider: "OTHER" } }))).status).toBe(400);
  });
});

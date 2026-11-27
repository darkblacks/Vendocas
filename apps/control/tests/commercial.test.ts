import { beforeAll, beforeEach, describe, expect, it } from "vitest";
import { FakeGitHub, bootstrapOwner, code, cookieFrom, reauth, req, resetDb, sql } from "./helpers";

/** Control Center ↔ Vendocas API: markets, prices, quotas and merchants (fake API that checks the admin token). */
const STATUS_TOKEN = "status-token-0123456789abcdefghijklmnop";
const ADMIN_TOKEN = "admin-token-0123456789abcdefghijklmnopq";
const MERCHANT = "0f8fad5b-d9cb-469f-a165-70867728950e";

function fakeApi(host: string) {
  const calls: { method: string; path: string; headers: Record<string, string>; body: unknown }[] = [];
  const market = { code: "BR", name: "Brasil", currency: "BRL", defaultLocale: "pt-BR", locales: ["pt-BR"], channelOrder: ["WHATSAPP", "EMAIL", "SMS"],
    sendWindowStart: "08:00:00", sendWindowEnd: "21:00:00", enabled: true, version: 0 };
  const handler = async (url: URL, init?: RequestInit): Promise<Response | null> => {
    if (url.host !== host) return null;
    const h = Object.fromEntries(Object.entries((init?.headers ?? {}) as Record<string, string>).map(([k, v]) => [k.toLowerCase(), v]));
    if (url.pathname === "/internal/status") return Response.json({ status: "UP", database: { up: true }, redis: { up: true }, workers: {}, recentErrors: [], settingsWritable: true });
    if (!url.pathname.startsWith("/internal/commercial") && !url.pathname.startsWith("/internal/merchants")) return new Response(null, { status: 404 });
    if (h["x-control-admin-token"] !== ADMIN_TOKEN) return new Response(null, { status: 401 });
    const body = init?.body ? JSON.parse(String(init.body)) : undefined;
    calls.push({ method: init?.method ?? "GET", path: url.pathname + url.search, headers: h, body });
    if (url.pathname === "/internal/commercial/markets") return Response.json([{ market, countries: [{ countryCode: "BR", defaultLocale: "pt-BR", currency: "BRL" }], merchants: 3 }]);
    if (url.pathname === "/internal/commercial/pricing") return Response.json([{ market: "BR", currency: "BRL", enabled: true, plans: [] }]);
    if (url.pathname === "/internal/commercial/prices/BR/ALERTA") {
      if (body.stripePriceId === "price_taken123") return Response.json({ code: "CONFLICT", detail: "This Stripe price is already used by another plan or market" }, { status: 409 });
      return Response.json({ id: "p1", currency: "BRL", unitAmountMinor: body.unitAmountMinor, stripePriceId: body.stripePriceId, marketCode: "BR", trialDays: 0 });
    }
    if (url.pathname.startsWith("/internal/commercial/quotas/")) return Response.json([{ channel: "WHATSAPP", monthlyLimit: body.monthlyLimit }]);
    if (url.pathname === "/internal/commercial/markets/BR") return Response.json({ ...market, ...body, version: 1 });
    if (url.pathname === "/internal/merchants") return Response.json({ items: [{ id: MERCHANT, tradeName: "Transmassa", market: "BR", health: [] }], total: 1, page: 0, size: 50 });
    if (url.pathname === `/internal/merchants/${MERCHANT}`) return Response.json({ merchant: { id: MERCHANT, tradeName: "Transmassa" }, channels: [], quotas: [] });
    return Response.json({ code: "NOT_FOUND", detail: "Merchant not found" }, { status: 404 });
  };
  return { calls, handler };
}

describe("commercial screens through the Control Center", () => {
  let owner: Awaited<ReturnType<typeof bootstrapOwner>>;
  const api = fakeApi("api-homolog.test");

  beforeAll(async () => {
    await resetDb();
    owner = await bootstrapOwner();
    new FakeGitHub().install(async (url, init) => api.handler(url, init));
    await reauth(owner);
    const v = await import("@/app/api/vault/[provider]/route");
    const r = await v.PUT(req("PUT", "/api/vault/VENDOCAS_API", { client: owner, body: { environment: "TEST", values: { baseUrl: "https://api-homolog.test", statusToken: STATUS_TOKEN, adminToken: ADMIN_TOKEN } } }),
      { params: Promise.resolve({ provider: "VENDOCAS_API" }) });
    expect(r.status).toBe(200);
  });
  beforeEach(async () => { await reauth(owner); });

  it("reads markets and prices with the admin token server-side only", async () => {
    const r = await (await import("@/app/api/commercial/route")).GET(req("GET", "/api/commercial?target=TEST", { client: owner }));
    const text = await r.text();
    expect(r.status).toBe(200);
    expect(text).not.toContain(ADMIN_TOKEN);
    expect(JSON.parse(text)).toMatchObject({ target: "TEST", markets: [{ market: { code: "BR" }, merchants: 3 }], pricing: [{ market: "BR" }] });
  });

  it("sets a market price as the OWNER, with the operator sent to the API and audited", async () => {
    const route = await import("@/app/api/commercial/price/route");
    const r = await route.PUT(req("PUT", "/api/commercial/price", { client: owner, body: { target: "TEST", market: "BR", plan: "ALERTA", unitAmountMinor: 9900, stripePriceId: "price_brAlerta01", trialDays: 7 } }));
    expect(r.status).toBe(200);
    const call = api.calls.findLast((c) => c.path === "/internal/commercial/prices/BR/ALERTA")!;
    expect(call.method).toBe("PUT");
    expect(call.headers["x-control-actor"]).toBe("owner@example.com");
    expect(call.body).toMatchObject({ unitAmountMinor: 9900, stripePriceId: "price_brAlerta01", trialDays: 7 });
    const a = await sql("SELECT outcome, detail::text AS d FROM cc_audit_events WHERE action = 'commercial.price_set'");
    expect(a.at(-1)).toMatchObject({ outcome: "SUCCESS" });
    expect(JSON.stringify(a)).not.toContain(ADMIN_TOKEN);
  });

  it("passes API conflicts through with the API's explanation", async () => {
    const route = await import("@/app/api/commercial/price/route");
    const r = await route.PUT(req("PUT", "/api/commercial/price", { client: owner, body: { target: "TEST", market: "BR", plan: "ALERTA", unitAmountMinor: 9900, stripePriceId: "price_taken123" } }));
    expect(r.status).toBe(409);
    expect((await r.json()).message ?? "").toContain("already used");
  });

  it("validates markets, plans and values before calling the API", async () => {
    const before = api.calls.length;
    const price = await import("@/app/api/commercial/price/route");
    expect((await price.PUT(req("PUT", "/api/commercial/price", { client: owner, body: { target: "TEST", market: "MARS", plan: "ALERTA", unitAmountMinor: 100 } }))).status).toBe(400);
    expect((await price.PUT(req("PUT", "/api/commercial/price", { client: owner, body: { target: "TEST", market: "BR", plan: "ALERTA", unitAmountMinor: -1 } }))).status).toBe(400);
    expect((await price.PUT(req("PUT", "/api/commercial/price", { client: owner, body: { target: "TEST", market: "BR", plan: "ALERTA", unitAmountMinor: 100, stripePriceId: "sk_live_x" } }))).status).toBe(400);
    expect(api.calls.length).toBe(before);
    const quota = await import("@/app/api/commercial/quota/route");
    const q = await quota.PUT(req("PUT", "/api/commercial/quota", { client: owner, body: { target: "TEST", market: "BR", plan: "ALERTA", channel: "WHATSAPP", monthlyLimit: 500 } }));
    expect(q.status).toBe(200);
    const market = await import("@/app/api/commercial/market/route");
    const m = await market.PUT(req("PUT", "/api/commercial/market", { client: owner, body: { target: "TEST", market: "BR", enabled: false, expectedVersion: 0 } }));
    expect(m.status).toBe(200);
    expect((await m.json()).enabled).toBe(false);
  });

  it("requires recent re-authentication to change prices, and ADMIN may read merchants but not change prices", async () => {
    await sql("UPDATE cc_sessions SET reauth_at = now() - interval '1 hour'");
    const price = await import("@/app/api/commercial/price/route");
    const stale = await price.PUT(req("PUT", "/api/commercial/price", { client: owner, body: { target: "TEST", market: "BR", plan: "ALERTA", unitAmountMinor: 100 } }));
    expect((await stale.json()).code).toBe("REAUTH_REQUIRED");
    await reauth(owner);
    const users = await import("@/app/api/users/route");
    const created = await (await users.POST(req("POST", "/api/users", { client: owner, body: { email: "sales-ops@example.com", displayName: "Ops", role: "ADMIN" } }))).json();
    const login = await import("@/app/api/auth/login/route");
    const step = await (await login.POST(req("POST", "/api/auth/login", { body: { email: "sales-ops@example.com", password: created.temporaryPassword } }))).json();
    const e = await (await import("@/app/api/auth/enroll/route")).POST(req("POST", "/api/auth/enroll", { body: { enrollToken: step.enroll.enrollToken, code: code(step.enroll.secret) } }));
    const admin = { cookie: cookieFrom(e), csrf: (await e.json()).csrfToken };
    const list = await (await import("@/app/api/merchants/route")).GET(req("GET", "/api/merchants?target=TEST&market=BR&q=trans", { client: admin }));
    expect(list.status).toBe(200);
    expect((await list.json()).items[0].tradeName).toBe("Transmassa");
    expect(api.calls.findLast((c) => c.path.startsWith("/internal/merchants?"))!.path).toContain("market=BR");
    const one = await (await import("@/app/api/merchants/[id]/route")).GET(req("GET", `/api/merchants/${MERCHANT}?target=TEST`, { client: admin }),
      { params: Promise.resolve({ id: MERCHANT }) });
    expect(one.status).toBe(200);
    const bad = await (await import("@/app/api/merchants/[id]/route")).GET(req("GET", "/api/merchants/x?target=TEST", { client: admin }), { params: Promise.resolve({ id: "../status" }) });
    expect(bad.status).toBe(400);
    expect((await price.PUT(req("PUT", "/api/commercial/price", { client: admin, body: { target: "TEST", market: "BR", plan: "ALERTA", unitAmountMinor: 100 } }))).status).toBe(403);
  });
});

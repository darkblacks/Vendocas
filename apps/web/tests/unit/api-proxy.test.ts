import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as proxy from "@/app/api/v1/[...path]/route";
import * as health from "@/app/api/health/route";

const ctx = (...path: string[]) => ({ params: Promise.resolve({ path }) });

describe("runtime proxy to the Vendocas API", () => {
  let fetchMock: ReturnType<typeof vi.fn>;
  beforeEach(() => { fetchMock = vi.fn(); vi.stubGlobal("fetch", fetchMock); vi.stubEnv("API_INTERNAL_URL", "https://api.example.test/"); });
  afterEach(() => { vi.unstubAllGlobals(); vi.unstubAllEnvs(); });

  it("reads API_INTERNAL_URL per request and forwards method, path, query, raw body, headers and cookies", async () => {
    fetchMock.mockResolvedValue(new Response('{"ok":true}', { status: 201, headers: [["content-type", "application/json"], ["set-cookie", "vd_refresh=a; Path=/api/v1/auth; HttpOnly"], ["set-cookie", "other=b; Path=/"]] }));
    const body = '{"id":"evt_1","type":"invoice.paid"}';
    const r = await proxy.POST(new Request("https://vendocas.netlify.app/api/v1/webhooks/stripe?x=1", {
      method: "POST", body, headers: { "content-type": "application/json", "stripe-signature": "t=1,v1=abc", authorization: "Bearer t", cookie: "vd_refresh=old", "x-nf-client-connection-ip": "203.0.113.9" },
    }), ctx("webhooks", "stripe"));
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe("https://api.example.test/api/v1/webhooks/stripe?x=1");
    expect(init.method).toBe("POST");
    expect(new TextDecoder().decode(init.body)).toBe(body); // byte-exact: webhook signatures stay valid
    expect(init.headers.get("stripe-signature")).toBe("t=1,v1=abc");
    expect(init.headers.get("authorization")).toBe("Bearer t");
    expect(init.headers.get("cookie")).toBe("vd_refresh=old");
    expect(init.headers.get("x-forwarded-for")).toBe("203.0.113.9");
    expect(init.headers.get("host")).toBeNull();
    expect(init.redirect).toBe("manual");
    expect(r.status).toBe(201);
    expect(r.headers.getSetCookie()).toEqual(["vd_refresh=a; Path=/api/v1/auth; HttpOnly", "other=b; Path=/"]);
    // a new value takes effect immediately (no rebuild)
    vi.stubEnv("API_INTERNAL_URL", "https://other-api.example.test");
    fetchMock.mockResolvedValue(new Response("[]"));
    await proxy.GET(new Request("https://vendocas.netlify.app/api/v1/billing/plans?currency=BRL"), ctx("billing", "plans"));
    expect(fetchMock.mock.calls[1]![0]).toBe("https://other-api.example.test/api/v1/billing/plans?currency=BRL");
  });

  it("explains instead of answering a bare 500", async () => {
    vi.stubEnv("API_INTERNAL_URL", "");
    vi.stubEnv("NODE_ENV", "production");
    let r = await proxy.GET(new Request("https://x/api/v1/billing/plans"), ctx("billing", "plans"));
    expect(r.status).toBe(502);
    expect((await r.json()).code).toBe("API_NOT_CONFIGURED");
    expect(fetchMock).not.toHaveBeenCalled();

    vi.stubEnv("API_INTERNAL_URL", "https://api.example.test");
    fetchMock.mockRejectedValueOnce(new TypeError("fetch failed"));
    r = await proxy.GET(new Request("https://x/api/v1/billing/plans"), ctx("billing", "plans"));
    expect((await r.json()).code).toBe("API_UNREACHABLE");

    fetchMock.mockResolvedValueOnce(new Response(null, { status: 302, headers: { location: "https://github.com/login?return_to=..." } }));
    r = await proxy.GET(new Request("https://x/api/v1/billing/plans"), ctx("billing", "plans"));
    expect((await r.json()).code).toBe("API_PRIVATE_PORT");
  });

  it("/api/health reports up, or the reason it is down", async () => {
    fetchMock.mockResolvedValueOnce(Response.json({ status: "UP" }));
    expect(await (await health.GET()).json()).toEqual({ up: true });
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 302, headers: { location: "https://github.com/login" } }));
    expect(await (await health.GET()).json()).toMatchObject({ up: false, reason: "API_PRIVATE_PORT" });
    fetchMock.mockRejectedValueOnce(new TypeError("fetch failed"));
    const down = await health.GET();
    expect(down.status).toBe(503);
    expect(await down.json()).toMatchObject({ up: false, reason: "API_UNREACHABLE" });
    vi.stubEnv("API_INTERNAL_URL", "not a url");
    expect(await (await health.GET()).json()).toMatchObject({ reason: "API_INVALID_URL" });
  });
});

describe("API_INTERNAL_URL normalization", async () => {
  const { normalizeApiUrl } = await import("@/lib/server/upstream");
  it("accepts common paste variations", () => {
    const want = "https://refactored-doodle-vrx6gqpwrqvh6p9q-8080.app.github.dev";
    for (const v of [want, `${want}/`, ` ${want} `, `"${want}"`, `'${want}'`, `<${want}>`, "refactored-doodle-vrx6gqpwrqvh6p9q-8080.app.github.dev",
      `${want}/api/v1`, `${want}/api/v1/`, `${want}/actuator/health`]) expect(normalizeApiUrl(v), v).toBe(want);
    expect(normalizeApiUrl("http://localhost:8080")).toBe("http://localhost:8080");
    expect(normalizeApiUrl("https://api.example.com/base")).toBe("https://api.example.com/base");
  });
  it("still rejects things that are not addresses", () => {
    for (const v of ["", "   ", "API_INTERNAL_URL", "ftp://x.example.com", "https://", "não sei"]) expect(normalizeApiUrl(v), v).toBeNull();
  });
});

describe("Origin handling (API CORS)", () => {
  let fetchMock: ReturnType<typeof vi.fn>;
  beforeEach(() => { fetchMock = vi.fn().mockResolvedValue(new Response("{}")); vi.stubGlobal("fetch", fetchMock); vi.stubEnv("API_INTERNAL_URL", "https://api.example.test"); });
  afterEach(() => { vi.unstubAllGlobals(); vi.unstubAllEnvs(); });

  it("drops the Origin of the site's own pages (server-to-server from here), so the API CORS filter does not answer 403", async () => {
    await proxy.POST(new Request("https://vendocas.netlify.app/api/v1/auth/register", { method: "POST", body: "{}", headers: { origin: "https://vendocas.netlify.app" } }), ctx("auth", "register"));
    expect(fetchMock.mock.calls[0]![1].headers.get("origin")).toBeNull();
  });
  it("keeps the Origin of other sites (tracking script on a merchant's store)", async () => {
    await proxy.POST(new Request("https://vendocas.netlify.app/api/v1/track/events", { method: "POST", body: "{}", headers: { origin: "https://loja.example.com" } }), ctx("track", "events"));
    expect(fetchMock.mock.calls[0]![1].headers.get("origin")).toBe("https://loja.example.com");
  });
});

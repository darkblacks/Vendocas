import { afterAll, beforeAll, describe, expect, it } from "vitest";
import http from "node:http";
import type { AddressInfo } from "node:net";
import pg from "pg";
import { randomBytes } from "node:crypto";
import { OWNER, code, cookieFrom, req } from "./helpers";
import { __setConfigStore, configStore, memoryConfigStore, type ConfigStore } from "@/server/config-store";
import { resetPool } from "@/server/db";
import { __setResolver, isBlockedIp, pgConfig, resolveDatabase, validateConnectionString } from "@/server/database-config";
import { handle } from "@/server/http";
import { __resetMemoryRateLimits } from "@/server/rate-limit";

/**
 * Full first installation with only CONTROL_SETUP_EMAIL + CONTROL_SETUP_KEY: no CONTROL_DATABASE_URL, the Neon-style
 * connection string is pasted through the API, persisted encrypted in the (external) config store.
 */
const TEST_URL = process.env.CONTROL_DATABASE_URL!;
const PASSWORD = new URL(TEST_URL).password;
const store = memoryConfigStore();
const IP = () => `198.51.100.${Math.floor(Math.random() * 250)}`;

type Route = { GET?: (r: Request) => Promise<Response>; POST?: (r: Request) => Promise<Response> };
const R = (p: string) => import(/* @vite-ignore */ `@/app/api/${p}/route`) as Promise<Route>;
const post = async (p: string, body: unknown, cookie: string | null, extra: { origin?: string | null; ip?: string } = {}) =>
  (await R(p)).POST!(req("POST", `/api/${p}`, { body, client: { cookie, csrf: null }, origin: extra.origin, headers: { "x-nf-client-connection-ip": extra.ip ?? IP() } }));
const session = (email = process.env.CONTROL_SETUP_EMAIL!, key = process.env.CONTROL_SETUP_KEY!, ip?: string) => post("bootstrap/session", { email, key }, null, { ip });

const sqlRaw = async (q: string) => {
  const c = new pg.Client({ connectionString: TEST_URL });
  await c.connect();
  try { return (await c.query(q)).rows; } finally { await c.end(); }
};
const noLeak = (text: string) => {
  expect(text).not.toContain(PASSWORD);
  expect(text).not.toMatch(/postgres(ql)?:\/\//);
  expect(text).not.toContain(process.env.CONTROL_SETUP_KEY!);
};
const hanging: ConfigStore = { get: () => new Promise(() => undefined), set: () => new Promise(() => undefined), delete: () => new Promise(() => undefined) };
const broken: ConfigStore = { get: async () => { throw new Error("boom"); }, set: async () => { throw new Error("boom"); }, delete: async () => undefined };

describe("installation with only CONTROL_SETUP_EMAIL and CONTROL_SETUP_KEY", () => {
  let setup: string | null = null;

  beforeAll(async () => {
    delete process.env.CONTROL_DATABASE_URL;
    __setConfigStore(store);
    __resetMemoryRateLimits();
    await sqlRaw("DROP SCHEMA public CASCADE; CREATE SCHEMA public;");
    resetPool();
  });
  afterAll(() => {
    process.env.CONTROL_DATABASE_URL = TEST_URL;
    __setConfigStore(null);
    __setResolver(null);
    resetPool();
  });

  it("GET /api/bootstrap answers instantly without database or store (even if the store hangs)", async () => {
    __setConfigStore(hanging);
    try {
      const t = Date.now();
      const r = await (await R("bootstrap")).GET!(req("GET", "/api/bootstrap"));
      expect(Date.now() - t).toBeLessThan(200);
      expect(r.status).toBe(200);
      expect(r.headers.get("content-type")).toContain("application/json");
      expect(await r.json()).toMatchObject({ setupKeyConfigured: true, setupEmailConfigured: true });
    } finally { __setConfigStore(store); }
  });

  it("reports missing variables as clear JSON", async () => {
    const key = process.env.CONTROL_SETUP_KEY;
    process.env.CONTROL_SETUP_KEY = "too-short";
    try {
      const r = await session(undefined, "too-short");
      expect(r.status).toBe(503);
      expect((await r.json()).code).toBe("SETUP_NOT_CONFIGURED");
      expect((await (await (await R("bootstrap")).GET!(req("GET", "/api/bootstrap"))).json()).setupKeyConfigured).toBe(false);
    } finally { process.env.CONTROL_SETUP_KEY = key; }
  });

  it("returns a clear JSON error quickly when the store hangs or fails (the old 504)", async () => {
    try {
      __setConfigStore(hanging);
      let t = Date.now();
      let r = await session();
      expect(Date.now() - t).toBeLessThan(6000);
      expect(r.status).toBe(503);
      expect(r.headers.get("content-type")).toContain("application/json");
      expect((await r.json()).code).toBe("CONFIG_STORE_TIMEOUT");
      __setConfigStore(broken);
      t = Date.now();
      r = await session();
      expect(Date.now() - t).toBeLessThan(2000);
      expect(r.status).toBeGreaterThanOrEqual(500);
      // wrong credentials never reveal store health
      __setConfigStore(hanging);
      r = await session(undefined, "wrong-key-" + "y".repeat(32));
      expect(r.status).toBe(401);
    } finally { __setConfigStore(store); }
  }, 15_000);

  it("rejects a wrong key or e-mail, and rate-limits across instances", async () => {
    const ip = "203.0.113.99";
    for (let i = 0; i < 10; i++) {
      const r = await session(process.env.CONTROL_SETUP_EMAIL, "wrong-key-" + "x".repeat(32), ip);
      expect(r.status).toBe(401);
      expect(cookieFrom(r)).toBeNull();
    }
    __resetMemoryRateLimits(); // simulate a different serverless instance: the store-backed counter still applies
    const blocked = await session(undefined, undefined, ip);
    expect(blocked.status).toBe(429);
    const wrongEmail = await session("intruder@example.com");
    expect((await wrongEmail.json()).code).toBe("SETUP_CREDENTIALS_INVALID");
  });

  it("opens a signed, HttpOnly setup session with the right e-mail + key (stage DATABASE)", async () => {
    const r = await session(" Owner@Example.com ");
    const text = await r.text();
    expect(r.status).toBe(200);
    expect(JSON.parse(text).stage).toBe("DATABASE");
    noLeak(text);
    expect(r.headers.get("set-cookie")).toMatch(/HttpOnly; SameSite=Strict; Max-Age=1800/);
    setup = cookieFrom(r);
    expect(setup).toBeTruthy();
  });

  it("protects the database step: setup session, signature and same origin", async () => {
    expect((await (await post("bootstrap/database/test", { connectionString: TEST_URL }, null)).json()).code).toBe("SETUP_SESSION_REQUIRED");
    const forged = setup!.replace(/\.[^.]+$/, ".AAAA");
    expect((await post("bootstrap/database/test", { connectionString: TEST_URL }, forged)).status).toBe(401);
    expect((await (await post("bootstrap/database/test", { connectionString: TEST_URL }, setup, { origin: "https://evil.example" })).json()).code).toBe("CSRF");
  });

  it("tests the connection without writing anything, with safe error codes", async () => {
    const wrong = new URL(TEST_URL); wrong.password = "definitely-wrong";
    let r = await post("bootstrap/database/test", { connectionString: wrong.toString() }, setup);
    let text = await r.text();
    expect(JSON.parse(text).code).toBe("DB_AUTH_FAILED");
    noLeak(text);
    r = await post("bootstrap/database/test", { connectionString: "postgresql://u:pw@ep-x.neon.tech/db" }, setup);
    expect(await r.json()).toMatchObject({ code: "INVALID_CONNECTION_STRING", reason: "TLS" });
    r = await post("bootstrap/database/test", { connectionString: TEST_URL }, setup);
    text = await r.text();
    expect(JSON.parse(text)).toMatchObject({ ok: true, canCreateTables: true, alreadyInitialized: false });
    noLeak(text);
    expect(store.data.get("database")).toBeUndefined();
    expect(await sqlRaw("SELECT to_regclass('public.cc_users')::text AS t")).toEqual([{ t: null }]);
  });

  it("saves: prepares the schema and stores the URL encrypted (AES-256-GCM, HKDF config key)", async () => {
    const r = await post("bootstrap/database", { connectionString: TEST_URL }, setup);
    const text = await r.text();
    expect(r.status).toBe(200);
    expect(JSON.parse(text).stage).toBe("OWNER");
    noLeak(text);
    const raw = store.data.get("database")!;
    noLeak(raw);
    const rec = JSON.parse(raw);
    expect(rec.sealed.keyVersion).toMatch(/^sk-[0-9a-f]{12}$/);
    expect(rec.lockedAt).toBeNull();
    expect(Number((await sqlRaw("SELECT count(*) FROM cc_schema_migrations"))[0].count)).toBeGreaterThanOrEqual(2);
    noLeak(JSON.stringify(await sqlRaw("SELECT * FROM cc_audit_events")));
    // a second save re-encrypts with a fresh nonce
    await post("bootstrap/database", { connectionString: TEST_URL }, setup);
    expect(JSON.parse(store.data.get("database")!).sealed.nonce).not.toBe(rec.sealed.nonce);
  });

  it("persists across instances and binds the data to CONTROL_SETUP_KEY", async () => {
    resetPool(); // new instance: nothing cached in memory
    expect((await resolveDatabase()).url).toBe(TEST_URL);
    const key = process.env.CONTROL_SETUP_KEY;
    process.env.CONTROL_SETUP_KEY = randomBytes(32).toString("base64url");
    try { await expect(resolveDatabase()).rejects.toMatchObject({ code: "SETUP_KEY_MISMATCH" }); } finally { process.env.CONTROL_SETUP_KEY = key; }
    const rec = JSON.parse(store.data.get("database")!);
    const ct = Buffer.from(rec.sealed.ciphertext, "base64"); ct[3] = ct[3]! ^ 1;
    store.data.set("database", JSON.stringify({ ...rec, sealed: { ...rec.sealed, ciphertext: ct.toString("base64") } }));
    await expect(resolveDatabase()).rejects.toMatchObject({ code: "CONFIG_DECRYPT_FAILED" });
    store.data.set("database", JSON.stringify(rec));
    const again = await session();
    expect((await again.json()).stage).toBe("OWNER");
  });

  it("creates the owner without privileges until MFA; concurrent installs yield exactly one owner", async () => {
    const mk = (name: string) => post("bootstrap/owner", { displayName: name, password: OWNER.password }, setup);
    const [a, b] = await Promise.all([mk("First"), mk("Second")]);
    expect([a.status, b.status].every((s) => s === 200)).toBe(true);
    const ca = await a.json(), cb = await b.json();
    // not usable yet: no ACTIVE account, login demands enrollment
    expect((await sqlRaw("SELECT count(*)::int AS n FROM cc_users WHERE status = 'ACTIVE'"))[0].n).toBe(0);
    const enroll = await import("@/app/api/auth/enroll/route");
    const results = await Promise.all([ca, cb].map((c) => enroll.POST(req("POST", "/api/auth/enroll", { body: { enrollToken: c.enrollToken, code: code(c.secret) } }))));
    const ok = results.filter((r) => r.status === 200);
    expect(ok).toHaveLength(1);
    expect(ok[0]!.headers.get("set-cookie")).toMatch(/vcc_setup=; Path=\/; HttpOnly; SameSite=Strict; Max-Age=0/);
    const owners = await sqlRaw("SELECT email, role, status FROM cc_users");
    expect(owners).toEqual([{ email: "owner@example.com", role: "OWNER", status: "ACTIVE" }]);
    expect((await sqlRaw("SELECT bootstrap_completed_at IS NOT NULL AS done FROM cc_system_state"))[0].done).toBe(true);
    expect(JSON.parse(store.data.get("database")!).lockedAt).not.toBeNull();
  });

  it("locks the installation: no new session, no database change, no second owner (even with an old setup cookie)", async () => {
    expect((await (await session()).json()).code).toBe("INSTALLATION_COMPLETE");
    expect((await (await post("bootstrap/database", { connectionString: TEST_URL }, setup)).json()).code).toBe("BOOTSTRAP_LOCKED");
    expect((await (await post("bootstrap/owner", { displayName: "Late", password: OWNER.password }, setup)).json()).code).toBe("BOOTSTRAP_LOCKED");
    const login = await (await import("@/app/api/auth/login/route")).POST(req("POST", "/api/auth/login", { body: { email: OWNER.email, password: OWNER.password } }));
    expect((await login.json()).step).toBe("MFA");
  });

  it("recovers when the stored configuration is lost: re-linking the same database locks immediately", async () => {
    store.data.delete("database");
    resetPool();
    const s = await session();
    expect((await s.json()).stage).toBe("DATABASE");
    const c = cookieFrom(s);
    const r = await (await post("bootstrap/database", { connectionString: TEST_URL }, c)).json();
    expect(r).toMatchObject({ stage: "DONE", alreadyInitialized: true });
    expect(JSON.parse(store.data.get("database")!).lockedAt).not.toBeNull();
    expect((await (await post("bootstrap/owner", { displayName: "Intruder", password: OWNER.password }, c)).json()).code).toBe("BOOTSTRAP_LOCKED");
    expect((await sqlRaw("SELECT count(*)::int AS n FROM cc_users"))[0].n).toBe(1);
  });
});

describe("SSRF protection and TLS for the pasted connection string", () => {
  afterAll(() => __setResolver(null));
  const neon = "postgresql://neondb_owner:npg_abc123@ep-cool-name-a1b2c3.us-east-2.aws.neon.tech/neondb?sslmode=require&channel_binding=require";

  it("accepts Neon direct and pooled URLs", () => {
    expect(() => validateConnectionString(neon, true)).not.toThrow();
    expect(() => validateConnectionString(neon.replace("a1b2c3.", "a1b2c3-pooler."), true)).not.toThrow();
  });
  it("rejects local, private, metadata addresses, odd ports and file-reading parameters in production", () => {
    for (const u of ["postgresql://u:p@localhost/db?sslmode=require", "postgresql://u:p@169.254.169.254/db?sslmode=require", "postgresql://u:p@[::1]/db?sslmode=require",
      "postgresql://u:p@db.example.com:22/db?sslmode=require", "postgresql://u:p@db.example.com/db?sslmode=require&sslkey=/etc/shadow", "postgresql://u:p@db.example.com/db?sslmode=prefer"]) {
      expect(() => validateConnectionString(u, true), u).toThrow();
    }
    expect(() => validateConnectionString("postgresql://u:p@localhost/db", false)).not.toThrow(); // local development only
  });
  it("classifies addresses", () => {
    for (const ip of ["10.1.2.3", "172.16.0.1", "192.168.1.1", "127.0.0.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "::1", "fd00::1", "fe80::1", "::ffff:10.0.0.1"]) expect(isBlockedIp(ip), ip).toBe(true);
    for (const ip of ["3.18.12.1", "52.1.2.3", "2600:1f18::1"]) expect(isBlockedIp(ip), ip).toBe(false);
  });
  it("resolves DNS once, refuses private answers, and pins the checked IP while TLS keeps the host name (SNI)", async () => {
    __setResolver(async () => ["10.0.0.7"]);
    await expect(pgConfig(new URL(neon), true)).rejects.toMatchObject({ code: "INVALID_CONNECTION_STRING" });
    __setResolver(async () => ["3.18.12.1", "10.0.0.7"]); // one bad answer is enough to refuse
    await expect(pgConfig(new URL(neon), true)).rejects.toMatchObject({ code: "INVALID_CONNECTION_STRING" });
    __setResolver(async () => ["3.18.12.1"]);
    const cfg = await pgConfig(new URL(neon), true);
    expect(cfg).toMatchObject({ host: "3.18.12.1", port: 5432, user: "neondb_owner", database: "neondb", ssl: { servername: "ep-cool-name-a1b2c3.us-east-2.aws.neon.tech", rejectUnauthorized: true } });
    expect(cfg.connectionTimeoutMillis).toBeLessThanOrEqual(6000);
  });
});

describe("timeouts always produce JSON", () => {
  it("handler deadline", async () => {
    const r = await handle(() => new Promise<Response>(() => undefined), 50)();
    expect(r.status).toBe(503);
    expect(await r.json()).toMatchObject({ code: "OPERATION_TIMEOUT" });
  });

  describe("real Netlify Blobs SDK against a misbehaving endpoint", () => {
    let server: http.Server;
    let mode: "hang" | "500" | "ok" = "ok";
    let hits = 0;
    const data = new Map<string, string>();
    beforeAll(async () => {
      server = http.createServer((rq, rs) => {
        hits++;
        if (mode === "hang") return; // never answers
        if (mode === "500") { rs.writeHead(500); rs.end("down"); return; }
        if (rq.method === "PUT") { let b = ""; rq.on("data", (c) => (b += c)); rq.on("end", () => { data.set(rq.url!, b); rs.writeHead(200); rs.end(); }); return; }
        if (data.has(rq.url!)) { rs.writeHead(200); rs.end(data.get(rq.url!)); } else { rs.writeHead(404); rs.end(); }
      });
      await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
      const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
      process.env.NETLIFY_BLOBS_CONTEXT = Buffer.from(JSON.stringify({ siteID: "site", token: "tkn", edgeURL: base, uncachedEdgeURL: base })).toString("base64");
      process.env.CONTROL_CONFIG_STORE = "netlify-blobs";
      __setConfigStore(null);
    });
    afterAll(async () => {
      delete process.env.NETLIFY_BLOBS_CONTEXT;
      delete process.env.CONTROL_CONFIG_STORE;
      __setConfigStore(null);
      server.closeAllConnections();
      await new Promise((r) => server.close(r));
    });

    it("works normally (strong consistency) when the endpoint is healthy", async () => {
      mode = "ok";
      const s = await configStore();
      await s.set("probe", "value-1");
      expect(await s.get("probe")).toBe("value-1");
      expect(await s.get("missing")).toBeNull();
    });
    it("a hanging endpoint fails in seconds, not after 30 s", async () => {
      mode = "hang";
      const t = Date.now();
      await expect((await configStore()).get("probe")).rejects.toMatchObject({ code: expect.stringMatching(/CONFIG_STORE_(TIMEOUT|UNAVAILABLE)/) });
      expect(Date.now() - t).toBeLessThan(5000);
    }, 15_000);
    it("HTTP 500 is not retried five times with 5-second pauses", async () => {
      mode = "500";
      hits = 0;
      const t = Date.now();
      await expect((await configStore()).get("probe")).rejects.toMatchObject({ code: "CONFIG_STORE_UNAVAILABLE" });
      expect(hits).toBe(1);
      expect(Date.now() - t).toBeLessThan(3000);
    });
  });
});

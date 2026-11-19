import { afterAll, beforeAll, describe, expect, it } from "vitest";
import pg from "pg";
import { randomBytes } from "node:crypto";
import { OWNER, code, cookieFrom, req } from "./helpers";
import { __setConfigStore, memoryConfigStore } from "@/server/config-store";
import { resetPool } from "@/server/db";
import { mapConnectionError, validateConnectionString } from "@/server/database-config";

// This suite exercises the "paste the Neon connection string" bootstrap, so the env override must be absent.
const TEST_URL = process.env.CONTROL_DATABASE_URL!;
const GOOD = TEST_URL; // local test database (TLS is only optional for localhost outside production)
const PASSWORD = new URL(TEST_URL).password;
const TOKEN = process.env.CONTROL_BOOTSTRAP_TOKEN!;
const store = memoryConfigStore();

type H = { GET?: (r: Request) => Promise<Response>; POST?: (r: Request) => Promise<Response> };
const dbRoute = () => import("@/app/api/bootstrap/database/route") as Promise<H>;
const bootRoute = () => import("@/app/api/bootstrap/route") as Promise<H>;
const status = async () => (await (await (await bootRoute()).GET!(req("GET", "/api/bootstrap"))).json());
const connect = async (connectionString: string, token = TOKEN) =>
  (await dbRoute()).POST!(req("POST", "/api/bootstrap/database", { body: { token, connectionString }, headers: { "x-nf-client-connection-ip": `198.51.100.${Math.floor(Math.random() * 250)}` } }));

async function wipe() {
  const c = new pg.Client({ connectionString: TEST_URL });
  await c.connect();
  await c.query("DROP SCHEMA public CASCADE; CREATE SCHEMA public;");
  await c.end();
}
const rows = async (q: string) => {
  const c = new pg.Client({ connectionString: TEST_URL });
  await c.connect();
  try { return (await c.query(q)).rows; } finally { await c.end(); }
};
const assertNoLeak = (text: string) => {
  expect(text).not.toContain(PASSWORD);
  expect(text).not.toMatch(/postgres(ql)?:\/\//);
};

describe("bootstrap: database from a pasted connection string", () => {
  beforeAll(async () => {
    delete process.env.CONTROL_DATABASE_URL;
    __setConfigStore(store);
    await wipe();
    resetPool();
  });
  afterAll(() => {
    process.env.CONTROL_DATABASE_URL = TEST_URL;
    __setConfigStore(null);
    resetPool();
  });

  it("starts at the DATABASE stage without any database", async () => {
    const s = await status();
    expect(s.stage).toBe("DATABASE");
    expect(s.database.configured).toBe(false);
    // other endpoints say so explicitly instead of crashing
    const me = await (await import("@/app/api/auth/me/route")).GET(req("GET", "/api/auth/me", { client: { cookie: "vcc_session=abc", csrf: null } }));
    expect(me.status).toBe(503);
    expect((await me.json()).code).toBe("DATABASE_NOT_CONFIGURED");
  });

  it("requires the bootstrap token", async () => {
    const r = await connect(GOOD, "x".repeat(40));
    expect(r.status).toBe(403);
    expect((await r.json()).code).toBe("BOOTSTRAP_TOKEN_INVALID");
    expect(store.data.size).toBe(0);
  });

  it("rejects malformed or unsafe strings without echoing them", async () => {
    const cases: [string, string][] = [
      ["mysql://u:p@db.example.com/x", "PROTOCOL"],
      ["postgresql://user@ep-x.neon.tech/neondb?sslmode=require", "CREDENTIALS"],
      ["postgresql://user:pw@ep-x.neon.tech/neondb", "TLS"],
      ["postgresql://user:pw@ep-x.neon.tech/neondb?sslmode=disable", "TLS"],
      ["postgresql://user:pw@ep-x.neon.tech/neondb?sslmode=require&sslrootcert=/etc/passwd", "PARAMETER"],
      ["postgresql://user:pw@ep-x.neon.tech/neondb?sslmode=require&host=/var/run/postgresql", "PARAMETER"],
      ["postgresql://user:pw@ep-x.neon.tech/?sslmode=require", "DATABASE"],
      ["postgresql://user:pw@10.0.0.5/db?sslmode=require", "HOST"],
      ["not a url at all", "FORMAT"],
    ];
    for (const [s, reason] of cases) {
      const r = await connect(s);
      const text = await r.text();
      expect(r.status, s).toBe(400);
      expect(JSON.parse(text)).toMatchObject({ code: "INVALID_CONNECTION_STRING", reason });
      expect(text).not.toContain("pw@");
    }
    expect(store.data.size).toBe(0);
  });

  it("reports connection failures with safe codes (wrong password, missing database, unreachable host)", async () => {
    const wrongPw = new URL(GOOD); wrongPw.password = "definitely-wrong";
    let r = await connect(wrongPw.toString());
    let text = await r.text();
    expect(JSON.parse(text).code).toBe("DB_AUTH_FAILED");
    expect(text).not.toContain("vendocas");
    const missing = new URL(GOOD); missing.pathname = "/no_such_db_" + randomBytes(3).toString("hex");
    r = await connect(missing.toString());
    expect((await r.json()).code).toBe("DB_NOT_FOUND");
    r = await connect(`postgresql://u:${PASSWORD}@127.0.0.1:1/x`);
    text = await r.text();
    expect(JSON.parse(text).code).toBe("DB_UNREACHABLE");
    assertNoLeak(text);
    expect(store.data.size).toBe(0);
  });

  it("tests, migrates and stores the URL encrypted; the response never contains it", async () => {
    const r = await connect(GOOD);
    const text = await r.text();
    expect(r.status).toBe(200);
    expect(JSON.parse(text)).toMatchObject({ ok: true, provider: "PostgreSQL", alreadyInitialized: false });
    assertNoLeak(text);
    const raw = store.data.get("database")!;
    expect(raw).toBeTruthy();
    assertNoLeak(raw); // encrypted at rest in the external store
    expect(JSON.parse(raw).sealed.keyVersion).toBe("v1");
    expect((await rows("SELECT count(*)::int AS n FROM cc_schema_migrations"))[0].n).toBeGreaterThan(0);
    const audit = await rows("SELECT action, target, detail::text AS d FROM cc_audit_events WHERE action = 'bootstrap.database_configured'");
    expect(audit).toHaveLength(1);
    assertNoLeak(JSON.stringify(audit));
    const s = await status();
    expect(s).toMatchObject({ stage: "OWNER", database: { configured: true, source: "store", locked: false, reachable: true } });
    assertNoLeak(JSON.stringify(s));
  });

  it("allows replacing the URL before the owner exists", async () => {
    const r = await connect(GOOD + (GOOD.includes("?") ? "&" : "?") + "application_name=vendocas-control");
    expect(r.status).toBe(200);
  });

  it("creates the owner with MFA, then locks the database step permanently", async () => {
    const boot = await bootRoute();
    const r1 = await boot.POST!(req("POST", "/api/bootstrap", { body: { token: TOKEN, ...OWNER } }));
    expect(r1.status).toBe(200);
    const ch = await r1.json();
    // before MFA enrollment nothing is locked yet
    expect(JSON.parse(store.data.get("database")!).lockedAt).toBeNull();
    const r2 = await (await import("@/app/api/auth/enroll/route")).POST(req("POST", "/api/auth/enroll", { body: { enrollToken: ch.enrollToken, code: code(ch.secret) } }));
    expect(r2.status).toBe(200);
    expect(cookieFrom(r2)).toBeTruthy();
    expect(JSON.parse(store.data.get("database")!).lockedAt).not.toBeNull();
    expect((await status()).stage).toBe("DONE");
    const before = store.data.get("database");
    const again = await connect(GOOD);
    expect(again.status).toBe(403);
    expect((await again.json()).code).toBe("BOOTSTRAP_LOCKED");
    expect(store.data.get("database")).toBe(before);
    const owner2 = await boot.POST!(req("POST", "/api/bootstrap", { body: { token: TOKEN, ...OWNER } }));
    expect((await owner2.json()).code).toBe("BOOTSTRAP_LOCKED");
  });

  it("re-links an already initialized database (lost config) and locks it immediately", async () => {
    store.data.clear();
    resetPool();
    expect((await status()).stage).toBe("DATABASE");
    const r = await (await connect(GOOD)).json();
    expect(r.alreadyInitialized).toBe(true);
    expect(JSON.parse(store.data.get("database")!).lockedAt).not.toBeNull();
    expect((await status()).stage).toBe("DONE");
  });

  it("re-encrypts the stored URL when the master key rotates", async () => {
    const { rotateDatabaseConfig } = await import("@/server/database-config");
    const v2 = randomBytes(32).toString("base64");
    const savedKeys = process.env.CONTROL_MASTER_KEYS;
    process.env.CONTROL_MASTER_KEYS = `${savedKeys},v2:${v2}`;
    process.env.CONTROL_ACTIVE_KEY_VERSION = "v2";
    try {
      expect(await rotateDatabaseConfig()).toBe(true);
      expect(JSON.parse(store.data.get("database")!).sealed.keyVersion).toBe("v2");
      process.env.CONTROL_MASTER_KEYS = `v2:${v2}`;
      resetPool();
      expect((await status()).database.reachable).toBe(true);
    } finally {
      process.env.CONTROL_MASTER_KEYS = savedKeys;
      process.env.CONTROL_ACTIVE_KEY_VERSION = "v1";
    }
  });

  it("refuses the step when the database comes from the environment", async () => {
    process.env.CONTROL_DATABASE_URL = TEST_URL;
    try {
      const r = await connect(GOOD);
      expect([403, 409]).toContain(r.status);
    } finally { delete process.env.CONTROL_DATABASE_URL; }
  });
});

describe("connection string validation (unit)", () => {
  it("accepts a typical Neon URL (direct and pooled)", () => {
    expect(() => validateConnectionString("postgresql://neondb_owner:npg_abc123@ep-cool-name-a1b2c3.us-east-2.aws.neon.tech/neondb?sslmode=require&channel_binding=require", true)).not.toThrow();
    expect(() => validateConnectionString("postgresql://neondb_owner:npg_abc123@ep-cool-name-a1b2c3-pooler.us-east-2.aws.neon.tech/neondb?sslmode=require", true)).not.toThrow();
  });
  it("requires TLS even for localhost in production", () => {
    expect(() => validateConnectionString("postgresql://u:p@localhost/db", true)).toThrow();
    expect(() => validateConnectionString("postgresql://u:p@localhost/db?sslmode=require", true)).toThrow(); // local hosts are not valid in production
    expect(() => validateConnectionString("postgresql://u:p@localhost/db", false)).not.toThrow();
  });
  it("maps driver errors without forwarding their text", () => {
    const e = mapConnectionError(Object.assign(new Error('password authentication failed for user "secret_user"'), { code: "28P01" }));
    expect(e.code).toBe("DB_AUTH_FAILED");
    expect(e.message).not.toContain("secret_user");
    expect(mapConnectionError(new Error("self-signed certificate")).code).toBe("DB_TLS_FAILED");
  });
});

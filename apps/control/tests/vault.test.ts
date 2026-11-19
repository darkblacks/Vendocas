import { beforeAll, beforeEach, describe, expect, it } from "vitest";
import { randomBytes } from "node:crypto";
import { FakeGitHub, bootstrapOwner, reauth, req, resetDb, sql } from "./helpers";
import { open, seal } from "@/server/crypto";
import { reveal } from "@/server/vault";

type H = { GET?: (r: Request, c?: unknown) => Promise<Response>; POST?: (r: Request, c?: unknown) => Promise<Response>; PUT?: (r: Request, c?: unknown) => Promise<Response> };
const vaultP = () => import("@/app/api/vault/[provider]/route") as Promise<H>;
const ctxFor = (provider: string) => ({ params: Promise.resolve({ provider }) });
const STRIPE_KEY = "sk_test_51SuperSecretValueABCDEFG";
const WHSEC = "whsec_SecretWebhookValue123";

describe("credentials vault", () => {
  let owner: Awaited<ReturnType<typeof bootstrapOwner>>;
  const gh = new FakeGitHub();
  let stripeStatus = 200;

  beforeAll(async () => {
    await resetDb();
    owner = await bootstrapOwner();
    gh.install(async (url) => {
      if (url.host === "api.stripe.com") {
        // A hostile/buggy provider echoing the key back must not leak it into our responses or DB.
        return new Response(JSON.stringify({ error: { message: `Invalid API Key provided: ${STRIPE_KEY}` } }), { status: stripeStatus });
      }
      return null;
    });
  });
  beforeEach(async () => { await reauth(owner); });

  it("stores credentials encrypted and never returns them", async () => {
    const v = await vaultP();
    const res = await v.PUT!(req("PUT", "/api/vault/STRIPE", { client: owner, body: { environment: "TEST", values: { secretKey: STRIPE_KEY, webhookSecret: WHSEC } } }), ctxFor("STRIPE"));
    const text = await res.text();
    expect(res.status).toBe(200);
    expect(text).not.toContain(STRIPE_KEY);
    expect(text).not.toContain(WHSEC);
    expect(JSON.parse(text).masked.secretKey).toBe("••••DEFG");
    const row = (await sql("SELECT * FROM integration_credentials WHERE provider = 'STRIPE'"))[0]!;
    expect(JSON.stringify(row)).not.toContain(STRIPE_KEY);
    expect(row.encryption_key_version).toBe("v1");
    const list = await (await (await import("@/app/api/vault/route")).GET(req("GET", "/api/vault", { client: owner }))).text();
    expect(list).not.toContain(STRIPE_KEY);
    expect((await reveal("STRIPE", "TEST")).secretKey).toBe(STRIPE_KEY); // authorized server-side decryption
  });

  it("binds ciphertext to its record (tampering or swapping fails)", async () => {
    const s = seal("secret", "vault:a:STRIPE:TEST");
    expect(open(s, "vault:a:STRIPE:TEST")).toBe("secret");
    expect(() => open(s, "vault:b:STRIPE:TEST")).toThrow();
    const ct = Buffer.from(s.ciphertext, "base64"); ct[0] = ct[0]! ^ 1;
    expect(() => open({ ...s, ciphertext: ct.toString("base64") }, "vault:a:STRIPE:TEST")).toThrow();
  });

  it("replaces a credential with a new nonce and version", async () => {
    const before = (await sql("SELECT nonce, version FROM integration_credentials WHERE provider = 'STRIPE'"))[0]!;
    const v = await vaultP();
    await v.PUT!(req("PUT", "/api/vault/STRIPE", { client: owner, body: { environment: "TEST", values: { secretKey: "sk_test_51AnotherValueXYZW", webhookSecret: WHSEC } } }), ctxFor("STRIPE"));
    const after = (await sql("SELECT nonce, version, status FROM integration_credentials WHERE provider = 'STRIPE'"))[0]!;
    expect(after.version).toBe(before.version + 1);
    expect(after.nonce).not.toBe(before.nonce);
    expect(after.status).toBe("UNTESTED");
    expect((await reveal("STRIPE", "TEST")).secretKey).toBe("sk_test_51AnotherValueXYZW");
    await v.PUT!(req("PUT", "/api/vault/STRIPE", { client: owner, body: { environment: "TEST", values: { secretKey: STRIPE_KEY, webhookSecret: WHSEC } } }), ctxFor("STRIPE"));
  });

  it("validates fields and environment consistency", async () => {
    const v = await vaultP();
    const wrongEnv = await v.PUT!(req("PUT", "/api/vault/STRIPE", { client: owner, body: { environment: "TEST", values: { secretKey: "sk_live_51LiveKeyABCDEFGH", webhookSecret: WHSEC } } }), ctxFor("STRIPE"));
    expect(wrongEnv.status).toBe(400);
    const missing = await (await v.PUT!(req("PUT", "/api/vault/TWILIO", { client: owner, body: { environment: "TEST", values: { accountSid: "nope" } } }), ctxFor("TWILIO"))).json();
    expect(missing.fields).toMatchObject({ accountSid: "invalid format", authToken: "required" });
    const unknown = await v.PUT!(req("PUT", "/api/vault/NOPE", { client: owner, body: { environment: "TEST", values: {} } }), ctxFor("NOPE"));
    expect(unknown.status).toBe(404);
  });

  it("tests the connection and records failures without leaking the key", async () => {
    const v = await vaultP();
    stripeStatus = 200;
    const ok = await (await v.POST!(req("POST", "/api/vault/STRIPE", { client: owner, body: { environment: "TEST", action: "test" } }), ctxFor("STRIPE"))).json();
    expect(ok.ok).toBe(true);
    stripeStatus = 401;
    const bad = await v.POST!(req("POST", "/api/vault/STRIPE", { client: owner, body: { environment: "TEST", action: "test" } }), ctxFor("STRIPE"));
    const text = await bad.text();
    expect(JSON.parse(text)).toMatchObject({ ok: false, code: "AUTH_FAILED" });
    expect(text).not.toContain(STRIPE_KEY);
    const row = (await sql("SELECT status, last_error_code, last_error_message FROM integration_credentials WHERE provider = 'STRIPE'"))[0]!;
    expect(row).toMatchObject({ status: "INVALID", last_error_code: "AUTH_FAILED" });
    expect(JSON.stringify(await sql("SELECT * FROM cc_audit_events"))).not.toContain(STRIPE_KEY);
    expect(JSON.stringify(await sql("SELECT * FROM integration_credential_history"))).not.toContain(STRIPE_KEY);
  });

  it("fails closed when the master key is missing", async () => {
    const saved = process.env.CONTROL_MASTER_KEYS;
    delete process.env.CONTROL_MASTER_KEYS;
    try {
      const v = await vaultP();
      const r = await v.PUT!(req("PUT", "/api/vault/FOCUS_NFE", { client: owner, body: { environment: "TEST", values: { token: "abc123token" } } }), ctxFor("FOCUS_NFE"));
      expect(r.status).toBe(503);
      expect((await r.json()).code).toBe("CONFIG_MISSING");
      await expect(reveal("STRIPE", "TEST")).rejects.toThrow();
    } finally { process.env.CONTROL_MASTER_KEYS = saved; }
  });

  it("rotates the master key and keeps everything decryptable", async () => {
    const v2 = randomBytes(32).toString("base64");
    process.env.CONTROL_MASTER_KEYS = `${process.env.CONTROL_MASTER_KEYS},v2:${v2}`;
    process.env.CONTROL_ACTIVE_KEY_VERSION = "v2";
    const r = await (await (await import("@/app/api/vault/rotate/route")).POST(req("POST", "/api/vault/rotate", { client: owner }))).json();
    expect(r.activeVersion).toBe("v2");
    expect(r.credentials).toBeGreaterThanOrEqual(1);
    expect(r.users).toBeGreaterThanOrEqual(1);
    process.env.CONTROL_MASTER_KEYS = `v2:${v2}`; // old key removed from the environment
    expect((await reveal("STRIPE", "TEST")).secretKey).toBe(STRIPE_KEY);
    await reauth(owner); // MFA secret also re-encrypted
  });

  it("revokes: provider API when supported, local secret destroyed always", async () => {
    const v = await vaultP();
    await v.PUT!(req("PUT", "/api/vault/GITHUB", { client: owner, body: { environment: "LIVE", values: { token: "github_pat_" + "a".repeat(40) } } }), ctxFor("GITHUB"));
    const r = await (await v.POST!(req("POST", "/api/vault/GITHUB", { client: owner, body: { environment: "LIVE", action: "revoke" } }), ctxFor("GITHUB"))).json();
    expect(r.providerRevocation.ok).toBe(true);
    expect(gh.count("POST /credentials/revoke")).toBe(1);
    await expect(reveal("GITHUB", "LIVE")).rejects.toMatchObject({ code: "CREDENTIAL_NOT_FOUND" });
    const hist = await (await v.GET!(req("GET", "/api/vault/GITHUB", { client: owner }), ctxFor("GITHUB"))).json();
    expect(hist.map((h: { action: string }) => h.action)).toEqual(expect.arrayContaining(["CREATED", "REVOKED"]));
  });
});

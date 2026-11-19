import pg from "pg";
import { __setPool } from "@/server/db";
import { __setOutbound } from "@/server/outbound";
import { totp } from "@/server/totp";

export const ORIGIN = "https://control.test";
export const OWNER = { email: "owner@example.com", password: "Correct-Horse-Battery-77", displayName: "Owner" };

/** Fresh schema per test file (the app re-applies migrations on first use). */
export async function resetDb() {
  __setPool(null);
  const c = new pg.Client({ connectionString: process.env.CONTROL_DATABASE_URL });
  await c.connect();
  await c.query("DROP SCHEMA public CASCADE; CREATE SCHEMA public;");
  await c.end();
}

export async function sql(text: string, params: unknown[] = []) {
  const c = new pg.Client({ connectionString: process.env.CONTROL_DATABASE_URL });
  await c.connect();
  try { return (await c.query(text, params)).rows; } finally { await c.end(); }
}

export interface Client { cookie: string | null; csrf: string | null }

export function req(method: string, path: string, opts: { body?: unknown; client?: Client; origin?: string | null; csrf?: string | null; headers?: Record<string, string> } = {}) {
  const headers: Record<string, string> = { "user-agent": "vitest", "x-nf-client-connection-ip": "203.0.113.7", ...opts.headers };
  if (opts.body !== undefined) headers["content-type"] = "application/json";
  if (opts.client?.cookie) headers.cookie = opts.client.cookie;
  const origin = opts.origin === undefined ? ORIGIN : opts.origin;
  if (origin) headers.origin = origin;
  const csrf = opts.csrf === undefined ? opts.client?.csrf : opts.csrf;
  if (csrf) headers["x-csrf-token"] = csrf;
  return new Request(`${ORIGIN}${path}`, { method, headers, body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined });
}

export const cookieFrom = (res: Response) => res.headers.get("set-cookie")?.split(";")[0] ?? null;
export const code = (secret: string, offsetSteps = 0) => totp(secret, Math.floor(Date.now() / 30_000) + offsetSteps);

/** Bootstraps the owner end-to-end through the real route handlers and returns an authenticated client. */
export async function bootstrapOwner(): Promise<Client & { secret: string; recoveryCodes: string[] }> {
  const boot = await import("@/app/api/bootstrap/route");
  const enroll = await import("@/app/api/auth/enroll/route");
  const r1 = await boot.POST(req("POST", "/api/bootstrap", { body: { token: process.env.CONTROL_BOOTSTRAP_TOKEN, ...OWNER } }));
  if (r1.status !== 200) throw new Error(`bootstrap failed ${r1.status} ${await r1.text()}`);
  const ch = (await r1.json()) as { enrollToken: string; secret: string };
  const r2 = await enroll.POST(req("POST", "/api/auth/enroll", { body: { enrollToken: ch.enrollToken, code: code(ch.secret) } }));
  if (r2.status !== 200) throw new Error(`enroll failed ${r2.status} ${await r2.text()}`);
  const body = (await r2.json()) as { csrfToken: string; recoveryCodes: string[] };
  return { cookie: cookieFrom(r2), csrf: body.csrfToken, secret: ch.secret, recoveryCodes: body.recoveryCodes };
}

export async function reauth(client: Client & { secret: string }) {
  const r = await import("@/app/api/auth/reauth/route");
  const res = await r.POST(req("POST", "/api/auth/reauth", { client, body: { password: OWNER.password, code: code(client.secret) } }));
  if (res.status !== 200) throw new Error(`reauth failed ${res.status}`);
}

// ------------------------------------------------------------------ fake provider APIs
export class FakeGitHub {
  codespaces = new Map<string, { state: string; repo: string }>();
  calls: string[] = [];
  failNext: { path: RegExp; status: number }[] = [];
  down = false;
  tokenValid = true;
  stopFailures = 0;
  /** Simulated latency for start/stop to expose races. */
  latencyMs = 0;

  constructor() { this.codespaces.set("vendocas-main-abc123", { state: "Shutdown", repo: "darkblacks/Vendocas" }); }

  install(extra?: (url: URL, init?: RequestInit) => Promise<Response | null>) {
    __setOutbound(async (input, init) => {
      const url = new URL(input);
      if (extra) { const r = await extra(url, init); if (r) return r; }
      if (url.host !== "github.fake") throw new TypeError("unexpected host " + url.host);
      return this.handle(url, init);
    });
  }

  private json(status: number, body: unknown) { return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } }); }

  private cs(name: string) {
    const c = this.codespaces.get(name)!;
    return { name, display_name: name, state: c.state, repository: { full_name: c.repo }, git_status: { ref: "main" }, machine: { display_name: "4 cores" },
      last_used_at: new Date().toISOString(), idle_timeout_minutes: 30, web_url: `https://${name}.github.dev`, retention_expires_at: null };
  }

  private async handle(url: URL, init?: RequestInit): Promise<Response> {
    const method = init?.method ?? "GET";
    this.calls.push(`${method} ${url.pathname}`);
    if (this.down) throw new TypeError("fetch failed");
    const auth = new Headers(init?.headers).get("authorization");
    if (url.pathname !== "/credentials/revoke" && (!this.tokenValid || !auth?.startsWith("Bearer "))) return this.json(401, { message: "Bad credentials" });
    const f = this.failNext.findIndex((x) => x.path.test(`${method} ${url.pathname}`));
    if (f >= 0) { const st = this.failNext[f]!.status; this.failNext.splice(f, 1); return this.json(st, { message: "injected" }); }
    if (url.pathname === "/user") return this.json(200, { login: "owner" });
    if (url.pathname === "/user/codespaces") return this.json(200, { codespaces: [...this.codespaces.keys()].map((n) => this.cs(n)) });
    if (url.pathname === "/credentials/revoke") return new Response(null, { status: 202 });
    const m = url.pathname.match(/^\/user\/codespaces\/([^/]+)(\/(start|stop))?$/);
    if (m) {
      const c = this.codespaces.get(m[1]!);
      if (!c) return this.json(404, { message: "Not Found" });
      if (this.latencyMs) await new Promise((r) => setTimeout(r, this.latencyMs));
      if (m[3] === "start" && method === "POST") { c.state = "Starting"; return this.json(200, this.cs(m[1]!)); }
      if (m[3] === "stop" && method === "POST") {
        if (this.stopFailures > 0) { this.stopFailures--; return this.json(500, { message: "boom" }); }
        c.state = "ShuttingDown"; return this.json(202, this.cs(m[1]!));
      }
      return this.json(200, this.cs(m[1]!));
    }
    return this.json(404, { message: "Not Found" });
  }

  count(s: string) { return this.calls.filter((c) => c === s).length; }
}

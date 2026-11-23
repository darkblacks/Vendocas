import { describe, expect, it } from "vitest";
import pg from "pg";
import { MIGRATIONS } from "@/server/migrations";
import { sha256 } from "@/server/crypto";
import { resetDb, req } from "./helpers";

/** Deploying the new session model must not sign out the administrator who is using the panel right now. */
describe("upgrade from 12-hour sessions", () => {
  it("keeps current sessions valid (extended to the new lifetime) and makes the browser trusted on the next request", async () => {
    await resetDb();
    const c = new pg.Client({ connectionString: process.env.CONTROL_DATABASE_URL });
    await c.connect();
    try {
      // database exactly as the previous release left it (migrations 1-2), with a live 12 h session
      await c.query("CREATE TABLE cc_schema_migrations (id int PRIMARY KEY, name text NOT NULL, applied_at timestamptz NOT NULL DEFAULT now())");
      for (const m of MIGRATIONS.filter((m) => m.id <= 2)) { await c.query(m.sql); await c.query("INSERT INTO cc_schema_migrations (id, name) VALUES ($1,$2)", [m.id, m.name]); }
      await c.query(`INSERT INTO cc_users (id, email, display_name, password_hash, role, status, mfa_enabled_at)
                     VALUES ('11111111-1111-1111-1111-111111111111','owner@example.com','Owner','x','OWNER','ACTIVE', now())`);
      await c.query(`INSERT INTO cc_sessions (id, user_id, token_hash, csrf_token, reauth_at, expires_at, idle_expires_at, created_at)
                     VALUES ('22222222-2222-2222-2222-222222222222','11111111-1111-1111-1111-111111111111',$1,'csrf', now(), now() + interval '11 hours',
                             now() + interval '20 minutes', now() - interval '1 hour')`, [sha256("legacy-session-token")]);
    } finally { await c.end(); }

    const me = await import("@/app/api/auth/me/route"); // first request applies migration 3
    const r = await me.GET(req("GET", "/api/auth/me", { client: { cookie: "vcc_session=legacy-session-token", csrf: null } }));
    expect(r.status).toBe(200);
    expect(r.headers.getSetCookie().some((x) => x.startsWith("vcc_device="))).toBe(true); // trusted from now on
    // the browser's cookie had the old 12 h Max-Age: it is re-issued with the new lifetime right away
    const session = r.headers.getSetCookie().find((x) => x.startsWith("vcc_session="))!;
    expect(Number(/Max-Age=(\d+)/.exec(session)![1])).toBeGreaterThan(28 * 86400);
    const c2 = new pg.Client({ connectionString: process.env.CONTROL_DATABASE_URL });
    await c2.connect();
    try {
      const s = (await c2.query("SELECT expires_at > now() + interval '28 days' AS long, idle_expires_at > now() + interval '6 days' AS idle FROM cc_sessions")).rows[0];
      expect(s).toEqual({ long: true, idle: true });
    } finally { await c2.end(); }
  });
});

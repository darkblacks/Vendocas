import { beforeAll, beforeEach, describe, expect, it } from "vitest";
import { FakeGitHub, bootstrapOwner, configureRepository, reauth, req, resetDb, sql } from "./helpers";
import { schedulerTick } from "@/server/codespaces";

const NAME = "vendocas-main-abc123";
const HOUR = 3600_000;

describe("six-hour session scheduler", () => {
  let owner: Awaited<ReturnType<typeof bootstrapOwner>>;
  let gh: FakeGitHub;

  beforeAll(async () => {
    await resetDb();
    owner = await bootstrapOwner();
    await reauth(owner);
    gh = new FakeGitHub();
    gh.install();
    const v = await import("@/app/api/vault/[provider]/route");
    await v.PUT(req("PUT", "/api/vault/GITHUB", { client: owner, body: { environment: "LIVE", values: { token: "github_pat_" + "c".repeat(40) } } }), { params: Promise.resolve({ provider: "GITHUB" }) });
    await configureRepository(owner);
    await (await import("@/app/api/codespaces/select/route")).POST(req("POST", "/api/codespaces/select", { client: owner, body: { name: NAME } }));
  });

  beforeEach(async () => {
    gh.calls = []; gh.stopFailures = 0; gh.down = false;
    gh.codespaces.get(NAME)!.state = "Shutdown";
    await sql("DELETE FROM cc_codespace_sessions");
    await sql("DELETE FROM cc_rate_limits");
  });

  async function startSession() {
    const res = await (await import("@/app/api/codespaces/start/route")).POST(req("POST", "/api/codespaces/start", { client: owner }));
    expect(res.status).toBe(200);
    gh.codespaces.get(NAME)!.state = "Available";
    return (await res.json()).session;
  }

  it("sets a deadline of at most six hours and requests the stop when it passes", async () => {
    const s = await startSession();
    const deadline = new Date(s.deadlineAt).getTime() - new Date(s.requestedAt).getTime();
    expect(Math.abs(deadline - 6 * HOUR)).toBeLessThan(5000);
    // 5h59: nothing happens besides confirming RUNNING
    await schedulerTick(new Date(Date.now() + 5.98 * HOUR));
    expect(gh.count(`POST /user/codespaces/${NAME}/stop`)).toBe(0);
    // 6h01: stop requested — but not reported STOPPED before GitHub confirms
    await sql("UPDATE cc_codespace_sessions SET next_check_at = now(), lease_until = NULL");
    const t = await schedulerTick(new Date(Date.now() + 6.02 * HOUR));
    expect(t.actions.some((a) => a.endsWith("STOP_REQUESTED"))).toBe(true);
    expect(gh.count(`POST /user/codespaces/${NAME}/stop`)).toBe(1);
    expect((await sql("SELECT state, stop_reason FROM cc_codespace_sessions"))[0]).toMatchObject({ state: "STOP_REQUESTED", stop_reason: "DEADLINE" });
    gh.codespaces.get(NAME)!.state = "Shutdown";
    await sql("UPDATE cc_codespace_sessions SET next_check_at = now(), lease_until = NULL");
    await schedulerTick(new Date(Date.now() + 6.1 * HOUR));
    expect((await sql("SELECT state FROM cc_codespace_sessions"))[0]!.state).toBe("STOPPED");
    // never restarted afterwards
    await schedulerTick(new Date(Date.now() + 7 * HOUR));
    expect(gh.count(`POST /user/codespaces/${NAME}/start`)).toBe(1);
    expect((await sql("SELECT count(*)::int AS n FROM cc_audit_events WHERE action = 'session.deadline_stop' AND outcome = 'SUCCESS'"))[0]!.n).toBe(1);
  });

  it("records an early shutdown by GitHub (inactivity) as ENDED_EXTERNALLY", async () => {
    await startSession();
    gh.codespaces.get(NAME)!.state = "Shutdown";
    await sql("UPDATE cc_codespace_sessions SET next_check_at = now()");
    await schedulerTick(new Date(Date.now() + 1 * HOUR));
    expect((await sql("SELECT state, stop_reason FROM cc_codespace_sessions"))[0]).toMatchObject({ state: "ENDED_EXTERNALLY", stop_reason: "STOPPED_BY_GITHUB" });
    expect(gh.count(`POST /user/codespaces/${NAME}/stop`)).toBe(0);
  });

  it("does not act twice when two scheduler runs overlap", async () => {
    await startSession();
    gh.latencyMs = 100;
    const later = new Date(Date.now() + 6.5 * HOUR);
    await Promise.all([schedulerTick(later), schedulerTick(later), schedulerTick(later)]);
    gh.latencyMs = 0;
    expect(gh.count(`POST /user/codespaces/${NAME}/stop`)).toBe(1);
  });

  it("recovers sessions after a crash (stale lease) and keeps working without the browser", async () => {
    await startSession();
    await sql("UPDATE cc_codespace_sessions SET lease_until = now() - interval '1 minute', next_check_at = now() - interval '1 hour'");
    await schedulerTick(new Date(Date.now() + 6.2 * HOUR));
    expect(gh.count(`POST /user/codespaces/${NAME}/stop`)).toBe(1);
  });

  it("retries a failed deadline stop with backoff and alerts", async () => {
    await startSession();
    gh.stopFailures = 2;
    const t1 = new Date(Date.now() + 6.2 * HOUR);
    await schedulerTick(t1);
    let row = (await sql("SELECT state, stop_attempts, last_error, next_check_at FROM cc_codespace_sessions"))[0]!;
    expect(row.state).toBe("RUNNING");
    expect(row.stop_attempts).toBe(1);
    expect(row.last_error).toBe("GITHUB_ERROR");
    expect(new Date(row.next_check_at).getTime()).toBeGreaterThan(Date.now());
    await sql("UPDATE cc_codespace_sessions SET next_check_at = now()");
    await schedulerTick(t1);
    await sql("UPDATE cc_codespace_sessions SET next_check_at = now()");
    await schedulerTick(t1);
    row = (await sql("SELECT state, stop_attempts FROM cc_codespace_sessions"))[0]!;
    expect(row.state).toBe("STOP_REQUESTED");
    expect((await sql("SELECT count(*)::int AS n FROM cc_audit_events WHERE action = 'session.deadline_stop' AND outcome = 'FAILURE'"))[0]!.n).toBe(2);
    expect((await sql("SELECT count(*)::int AS n FROM cc_operation_log WHERE category = 'scheduler'"))[0]!.n).toBeGreaterThanOrEqual(2);
  });

  it("rejects session limits above six hours", async () => {
    await reauth(owner);
    const s = await import("@/app/api/settings/route");
    expect((await s.PUT(req("PUT", "/api/settings", { client: owner, body: { maxSessionHours: 7 } }))).status).toBe(400);
    expect((await s.PUT(req("PUT", "/api/settings", { client: owner, body: { maxSessionHours: 4 } }))).status).toBe(200);
    const session = await startSession();
    expect(new Date(session.deadlineAt).getTime() - new Date(session.requestedAt).getTime()).toBeLessThan(4 * HOUR + 5000);
    await s.PUT(req("PUT", "/api/settings", { client: owner, body: { maxSessionHours: 6 } }));
  });
});

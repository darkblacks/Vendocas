import { beforeAll, beforeEach, describe, expect, it } from "vitest";
import { FakeGitHub, bootstrapOwner, configureRepository, reauth, req, resetDb, sql } from "./helpers";

type H = { GET?: (r: Request, c?: unknown) => Promise<Response>; POST?: (r: Request, c?: unknown) => Promise<Response>; PUT?: (r: Request, c?: unknown) => Promise<Response> };
const r = (p: string) => import(/* @vite-ignore */ `@/app/api/${p}/route`) as Promise<H>;
const NAME = "vendocas-main-abc123";

describe("codespace control", () => {
  let owner: Awaited<ReturnType<typeof bootstrapOwner>>;
  let gh: FakeGitHub;

  beforeAll(async () => {
    await resetDb();
    owner = await bootstrapOwner();
  });
  beforeEach(async () => {
    gh = new FakeGitHub();
    gh.codespaces.set("other-repo-cs", { state: "Available", repo: "someone/else" });
    gh.install();
    await sql("DELETE FROM cc_codespace_sessions");
    await sql("DELETE FROM cc_rate_limits");
    await reauth(owner);
    const vault = await import("@/app/api/vault/[provider]/route");
    await vault.PUT(req("PUT", "/api/vault/GITHUB", { client: owner, body: { environment: "LIVE", values: { token: "github_pat_" + "b".repeat(40) } } }), { params: Promise.resolve({ provider: "GITHUB" }) });
    await configureRepository(owner);
  });

  it("configures the repository in the UI: verified on GitHub, owner only, no environment variable", async () => {
    const s = await import("@/app/api/settings/route");
    await reauth(owner);
    expect((await s.PUT(req("PUT", "/api/settings", { client: owner, body: { githubRepository: "not a repo" } }))).status).toBe(400);
    const missing = await s.PUT(req("PUT", "/api/settings", { client: owner, body: { githubRepository: "someone/private-thing" } }));
    expect((await missing.json()).code).toBe("REPOSITORY_NOT_FOUND");
    const ok = await (await s.PUT(req("PUT", "/api/settings", { client: owner, body: { githubRepository: "DarkBlacks/vendocas", webAppUrl: "https://app.example.com/" } }))).json();
    expect(ok).toMatchObject({ repository: "darkblacks/Vendocas", webAppUrl: "https://app.example.com" });
    const got = await (await s.GET(req("GET", "/api/settings", { client: owner }))).json();
    expect(got).toMatchObject({ repository: "darkblacks/Vendocas", links: { web: "https://app.example.com" } });
    expect((await s.PUT(req("PUT", "/api/settings", { client: owner, body: { webAppUrl: "javascript:alert(1)" } }))).status).toBe(400);
  });

  async function selectMain() {
    const res = await (await r("codespaces/select")).POST!(req("POST", "/api/codespaces/select", { client: owner, body: { name: NAME } }));
    expect(res.status).toBe(200);
  }

  it("lists only codespaces of the configured repository and refuses foreign ones", async () => {
    const list = await (await (await r("codespaces/available")).GET!(req("GET", "/api/codespaces/available", { client: owner }))).json();
    expect(list.map((c: { name: string }) => c.name)).toEqual([NAME]);
    const foreign = await (await r("codespaces/select")).POST!(req("POST", "/api/codespaces/select", { client: owner, body: { name: "other-repo-cs" } }));
    expect(foreign.status).toBe(400);
    const missing = await (await r("codespaces/select")).POST!(req("POST", "/api/codespaces/select", { client: owner, body: { name: "../../etc" } }));
    expect(missing.status).toBe(400);
  });

  it("starts a stopped codespace and confirms RUNNING only after GitHub reports Available", async () => {
    await selectMain();
    const res = await (await (await r("codespaces/start")).POST!(req("POST", "/api/codespaces/start", { client: owner, headers: { "idempotency-key": "click-0001-abcd" } }))).json();
    expect(res.session.state).toBe("STARTING");
    expect(gh.count(`POST /user/codespaces/${NAME}/start`)).toBe(1);
    const { schedulerTick } = await import("@/server/codespaces");
    await sql("UPDATE cc_codespace_sessions SET next_check_at = now()");
    await schedulerTick();
    expect((await sql("SELECT state FROM cc_codespace_sessions"))[0]!.state).toBe("STARTING");
    gh.codespaces.get(NAME)!.state = "Available";
    await sql("UPDATE cc_codespace_sessions SET next_check_at = now()");
    await schedulerTick();
    expect((await sql("SELECT state, running_at FROM cc_codespace_sessions"))[0]).toMatchObject({ state: "RUNNING" });
  });

  it("handles an already running codespace without calling start", async () => {
    await selectMain();
    gh.codespaces.get(NAME)!.state = "Available";
    const res = await (await (await r("codespaces/start")).POST!(req("POST", "/api/codespaces/start", { client: owner }))).json();
    expect(res.alreadyRunning).toBe(true);
    expect(res.session.state).toBe("RUNNING");
    expect(gh.count(`POST /user/codespaces/${NAME}/start`)).toBe(0);
  });

  it("is idempotent for double clicks and concurrent requests", async () => {
    await selectMain();
    gh.latencyMs = 150;
    const start = await r("codespaces/start");
    const results = await Promise.all([1, 2, 3].map((i) => start.POST!(req("POST", "/api/codespaces/start", { client: owner, headers: { "idempotency-key": `click-concurrent-${i}` } }))));
    const ok = results.filter((x) => x.status === 200).length;
    const busy = results.filter((x) => x.status === 409).length;
    expect(ok + busy).toBe(3);
    expect(gh.count(`POST /user/codespaces/${NAME}/start`)).toBe(1);
    expect((await sql("SELECT count(*)::int AS n FROM cc_codespace_sessions"))[0]!.n).toBe(1);
    gh.latencyMs = 0;
    const again = await (await start.POST!(req("POST", "/api/codespaces/start", { client: owner, headers: { "idempotency-key": "click-concurrent-1" } }))).json();
    expect(again.alreadyRunning).toBe(true);
    expect(gh.count(`POST /user/codespaces/${NAME}/start`)).toBe(1);
  });

  it("stops and marks STOPPED only after GitHub confirms Shutdown; already-stopped is handled", async () => {
    await selectMain();
    gh.codespaces.get(NAME)!.state = "Available";
    await (await r("codespaces/start")).POST!(req("POST", "/api/codespaces/start", { client: owner }));
    const stop = await (await (await r("codespaces/stop")).POST!(req("POST", "/api/codespaces/stop", { client: owner }))).json();
    expect(stop.session.state).toBe("STOP_REQUESTED");
    const { schedulerTick } = await import("@/server/codespaces");
    gh.codespaces.get(NAME)!.state = "Shutdown";
    await sql("UPDATE cc_codespace_sessions SET next_check_at = now()");
    await schedulerTick();
    expect((await sql("SELECT state, stop_reason FROM cc_codespace_sessions"))[0]).toMatchObject({ state: "STOPPED", stop_reason: "MANUAL" });
    const again = await (await (await r("codespaces/stop")).POST!(req("POST", "/api/codespaces/stop", { client: owner }))).json();
    expect(again.alreadyStopped).toBe(true);
    expect(gh.count(`POST /user/codespaces/${NAME}/stop`)).toBe(1);
  });

  it("maps GitHub failures: invalid token, missing codespace, API down", async () => {
    await selectMain();
    const start = await r("codespaces/start");
    gh.tokenValid = false;
    let res = await start.POST!(req("POST", "/api/codespaces/start", { client: owner }));
    expect((await res.json()).code).toBe("GITHUB_AUTH_FAILED");
    gh.tokenValid = true;
    gh.codespaces.delete(NAME);
    res = await start.POST!(req("POST", "/api/codespaces/start", { client: owner }));
    expect(res.status).toBe(404);
    gh.down = true;
    res = await start.POST!(req("POST", "/api/codespaces/start", { client: owner }));
    expect((await res.json()).code).toBe("GITHUB_UNAVAILABLE");
    expect((await sql("SELECT count(*)::int AS n FROM cc_codespace_sessions"))[0]!.n).toBe(0);
    const ov = await (await (await r("codespaces")).GET!(req("GET", "/api/codespaces", { client: owner }))).json();
    expect(ov.error).toBe("GITHUB_UNAVAILABLE"); // dashboard shows the real problem, not a fake state
  });

  it("requires the GitHub token before any control", async () => {
    await reauth(owner);
    const v = await import("@/app/api/vault/[provider]/route");
    await v.POST(req("POST", "/api/vault/GITHUB", { client: owner, body: { environment: "LIVE", action: "disable" } }), { params: Promise.resolve({ provider: "GITHUB" }) });
    const res = await (await r("codespaces/start")).POST!(req("POST", "/api/codespaces/start", { client: owner }));
    expect((await res.json()).code).toBe("GITHUB_NOT_CONFIGURED");
  });
});

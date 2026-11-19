import type pg from "pg";
import { audit, oplog, SYSTEM, type Actor } from "./audit";
import type { AuthContext } from "./auth";
import { db, tx } from "./db";
import { HttpError, bad } from "./http";
import { RUNNING_STATES, STOPPED_STATES, getCodespace, listCodespaces, repositoryFullName, startCodespace, stopCodespace, type Codespace } from "./github";
import { reveal } from "./vault";

/**
 * Codespace lifecycle with a hard session limit (≤ 6 h, see cc_system_state.max_session_hours).
 * Rules: the browser never chooses which repository/account is controlled (server-side allowlist + selection
 * validated against GitHub); concurrent clicks are serialized with an advisory lock and a unique "open session"
 * index; a session is only marked RUNNING/STOPPED after GitHub reports that state.
 */
export interface SessionRow {
  id: string; codespaceName: string; state: string; requestedAt: string; runningAt: string | null; deadlineAt: string; stopReason: string | null;
  stopAttempts: number; lastGithubState: string | null; lastError: string | null; endedAt: string | null; startedBy: string | null;
}

export async function githubToken(): Promise<string> {
  try {
    return (await reveal("GITHUB", "LIVE")).token!;
  } catch (e) {
    if (e instanceof HttpError && e.code === "CREDENTIAL_NOT_FOUND") throw new HttpError(409, "GITHUB_NOT_CONFIGURED", "Store a validated GitHub token in Credentials first");
    throw e;
  }
}

export async function settings() {
  const p = await db();
  const s = (await p.query("SELECT selected_codespace, max_session_hours, github_repository, web_app_url FROM cc_system_state WHERE id = 1")).rows[0];
  return {
    selectedCodespace: (s?.selected_codespace as string | null) ?? null,
    maxSessionHours: Number(s?.max_session_hours ?? 6),
    repository: (s?.github_repository as string | null) ?? null,
    webAppUrl: (s?.web_app_url as string | null) ?? null,
  };
}

/** Server-side allowlist: only codespaces of the repository the OWNER configured are listed/controlled. */
async function allowedRepo(): Promise<string> {
  const repo = (await settings()).repository;
  if (!repo) throw new HttpError(409, "REPOSITORY_NOT_CONFIGURED", "Set the GitHub repository in Settings first");
  return repo.toLowerCase();
}

const REPO = /^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})\/[A-Za-z0-9._-]{1,100}$/;

export async function setRepository(ctx: AuthContext, repo: string) {
  const r = repo.trim();
  if (!REPO.test(r)) throw bad("Use the owner/name format, e.g. darkblacks/Vendocas");
  const canonical = await repositoryFullName(await githubToken(), r); // requires a stored GitHub token
  const p = await db();
  // Changing repository invalidates the selected codespace (it belonged to the previous one).
  await p.query(`UPDATE cc_system_state SET selected_codespace = CASE WHEN lower(coalesce(github_repository,'')) = lower($1) THEN selected_codespace END,
                 github_repository = $1, updated_at = now() WHERE id = 1`, [canonical]);
  await audit(ctx.actor, "settings.github_repository", "SUCCESS", canonical);
  return canonical;
}

export async function setWebAppUrl(ctx: AuthContext, url: string | null) {
  let v: string | null = null;
  if (url && url.trim()) {
    let u: URL;
    try { u = new URL(url.trim()); } catch { throw bad("Invalid URL"); }
    if (u.protocol !== "https:" && !(u.protocol === "http:" && u.hostname === "localhost")) throw bad("Use an https:// URL");
    v = u.origin + (u.pathname === "/" ? "" : u.pathname);
  }
  const p = await db();
  await p.query("UPDATE cc_system_state SET web_app_url = $1, updated_at = now() WHERE id = 1", [v]);
  await audit(ctx.actor, "settings.web_app_url", "SUCCESS", v);
  return v;
}

export async function available(): Promise<Codespace[]> {
  const repo = await allowedRepo();
  return (await listCodespaces(await githubToken())).filter((c) => c.repository.toLowerCase() === repo);
}

export async function select(ctx: AuthContext, name: string) {
  const match = (await available()).find((c) => c.name === name);
  if (!match) throw bad("This codespace does not exist or does not belong to the configured repository");
  const p = await db();
  await p.query("UPDATE cc_system_state SET selected_codespace = $1, updated_at = now() WHERE id = 1", [name]);
  await audit(ctx.actor, "codespace.select", "SUCCESS", name);
}

export async function setMaxHours(ctx: AuthContext, hours: number) {
  if (!(hours > 0 && hours <= 6)) throw bad("The session limit must be greater than 0 and at most 6 hours");
  const p = await db();
  await p.query("UPDATE cc_system_state SET max_session_hours = $1, updated_at = now() WHERE id = 1", [hours]);
  await audit(ctx.actor, "settings.max_session_hours", "SUCCESS", String(hours));
}

async function selected(): Promise<string> {
  const s = await settings();
  if (!s.selectedCodespace) throw new HttpError(409, "CODESPACE_NOT_SELECTED", "Select the Vendocas codespace in Server settings");
  return s.selectedCodespace;
}

const SESSION_COLS = `id, codespace_name AS "codespaceName", state, requested_at AS "requestedAt", running_at AS "runningAt", deadline_at AS "deadlineAt",
  stop_reason AS "stopReason", stop_attempts AS "stopAttempts", last_github_state AS "lastGithubState", last_error AS "lastError", ended_at AS "endedAt",
  (SELECT email FROM cc_users u WHERE u.id = s.started_by) AS "startedBy"`;

export async function openSession(name: string, c?: pg.PoolClient): Promise<SessionRow | null> {
  const q = c ?? (await db());
  return (await q.query(`SELECT ${SESSION_COLS} FROM cc_codespace_sessions s WHERE codespace_name = $1 AND state IN ('STARTING','RUNNING','STOP_REQUESTED')`, [name])).rows[0] ?? null;
}

export async function sessions(limit = 50): Promise<SessionRow[]> {
  const p = await db();
  return (await p.query(`SELECT ${SESSION_COLS} FROM cc_codespace_sessions s ORDER BY requested_at DESC LIMIT $1`, [limit])).rows;
}

/** Serializes operations on one codespace across all serverless instances. */
async function withCodespaceLock<T>(name: string, fn: (c: pg.PoolClient) => Promise<T>): Promise<T> {
  return tx(async (c) => {
    const got = (await c.query("SELECT pg_try_advisory_xact_lock(727002, hashtext($1)) AS ok", [name])).rows[0]?.ok;
    if (!got) throw new HttpError(409, "OPERATION_IN_PROGRESS", "Another operation on this codespace is in progress");
    return fn(c);
  });
}

export async function start(ctx: AuthContext, idempotencyKey: string | null): Promise<{ session: SessionRow; codespace: Codespace; alreadyRunning: boolean }> {
  if (idempotencyKey && !/^[A-Za-z0-9_-]{8,80}$/.test(idempotencyKey)) throw bad("Invalid Idempotency-Key");
  const name = await selected();
  const token = await githubToken();
  const s = await settings();
  return withCodespaceLock(name, async (c) => {
    if (idempotencyKey) {
      const prior = (await c.query(`SELECT ${SESSION_COLS} FROM cc_codespace_sessions s WHERE idempotency_key = $1`, [idempotencyKey])).rows[0];
      if (prior) return { session: prior, codespace: await getCodespace(token, name), alreadyRunning: true };
    }
    const existing = await openSession(name, c);
    if (existing) return { session: existing, codespace: await getCodespace(token, name), alreadyRunning: true };
    let cs = await getCodespace(token, name);
    if (cs.repository.toLowerCase() !== (await allowedRepo())) throw new HttpError(403, "REPOSITORY_MISMATCH", "Selected codespace no longer belongs to the configured repository");
    if (cs.state === "ShuttingDown") throw new HttpError(409, "CODESPACE_SHUTTING_DOWN", "The codespace is shutting down; start it again once it has stopped");
    const alreadyRunning = RUNNING_STATES.has(cs.state);
    if (!alreadyRunning && cs.state !== "Starting") {
      try {
        cs = await startCodespace(token, name);
      } catch (e) {
        await audit(ctx.actor, "codespace.start", "FAILURE", name, { code: (e as HttpError).code }, c);
        await oplog("codespace", "ERROR", `Start failed for ${name}`, { code: (e as HttpError).code });
        throw e;
      }
    }
    const row = (await c.query(`INSERT INTO cc_codespace_sessions (id, codespace_name, started_by, state, deadline_at, running_at, last_github_state, idempotency_key, next_check_at)
       VALUES (gen_random_uuid(), $1, $2, $3, now() + make_interval(secs => $4), $5, $6, $7, now() + interval '1 minute')
       RETURNING id`, [name, ctx.user.id, RUNNING_STATES.has(cs.state) ? "RUNNING" : "STARTING", Math.round(s.maxSessionHours * 3600),
      RUNNING_STATES.has(cs.state) ? new Date() : null, cs.state, idempotencyKey])).rows[0];
    await audit(ctx.actor, "codespace.start", "SUCCESS", name, { alreadyRunning, githubState: cs.state }, c);
    await oplog("codespace", "INFO", `Session started for ${name} (${cs.state})`, { sessionId: row.id });
    const session = (await c.query(`SELECT ${SESSION_COLS} FROM cc_codespace_sessions s WHERE id = $1`, [row.id])).rows[0];
    return { session, codespace: cs, alreadyRunning };
  });
}

export async function stop(ctx: AuthContext): Promise<{ codespace: Codespace; session: SessionRow | null; alreadyStopped: boolean }> {
  const name = await selected();
  const token = await githubToken();
  return withCodespaceLock(name, async (c) => {
    let cs = await getCodespace(token, name);
    const open = await openSession(name, c);
    if (STOPPED_STATES.has(cs.state)) {
      if (open) await closeSession(c, open.id, open.state === "STOP_REQUESTED" ? "STOPPED" : "ENDED_EXTERNALLY", cs.state, open.stopReason ?? "ALREADY_STOPPED");
      await audit(ctx.actor, "codespace.stop", "SUCCESS", name, { alreadyStopped: true }, c);
      return { codespace: cs, session: open, alreadyStopped: true };
    }
    if (cs.state !== "ShuttingDown") {
      try {
        cs = await stopCodespace(token, name);
      } catch (e) {
        await audit(ctx.actor, "codespace.stop", "FAILURE", name, { code: (e as HttpError).code }, c);
        throw e;
      }
    }
    if (open) {
      await c.query(`UPDATE cc_codespace_sessions SET state = 'STOP_REQUESTED', stop_reason = COALESCE(stop_reason, 'MANUAL'), stop_requested_at = now(),
                       stop_attempts = stop_attempts + 1, last_github_state = $2, next_check_at = now() + interval '1 minute' WHERE id = $1`, [open.id, cs.state]);
    }
    await audit(ctx.actor, "codespace.stop", "SUCCESS", name, { githubState: cs.state }, c);
    return { codespace: cs, session: open ? await openSession(name, c) : null, alreadyStopped: false };
  });
}

async function closeSession(c: pg.PoolClient | pg.Pool, id: string, state: "STOPPED" | "ENDED_EXTERNALLY" | "FAILED", githubState: string, reason: string) {
  await c.query(`UPDATE cc_codespace_sessions SET state = $2, last_github_state = $3, stop_reason = COALESCE(stop_reason, $4), ended_at = now(),
                   lease_until = NULL, last_error = CASE WHEN $2 = 'FAILED' THEN last_error ELSE NULL END WHERE id = $1`, [id, state, githubState, reason]);
}

/**
 * Scheduler tick (Netlify scheduled function, every 5 minutes; independent of browser visits and of the Codespace).
 * Each open session is leased with FOR UPDATE SKIP LOCKED so duplicated invocations never act twice.
 * It never starts a codespace.
 */
export async function schedulerTick(now = new Date(), actor: Actor = SYSTEM): Promise<{ checked: number; actions: string[] }> {
  const p = await db();
  const leased = (await p.query(`UPDATE cc_codespace_sessions SET lease_until = now() + interval '2 minutes'
       WHERE id IN (SELECT id FROM cc_codespace_sessions WHERE state IN ('STARTING','RUNNING','STOP_REQUESTED')
                      AND (next_check_at <= $1 OR deadline_at <= $1) AND (lease_until IS NULL OR lease_until < now())
                    FOR UPDATE SKIP LOCKED)
       RETURNING id, codespace_name, state, deadline_at, stop_attempts, stop_requested_at`, [now])).rows;
  const actions: string[] = [];
  if (!leased.length) return { checked: 0, actions };
  let token: string;
  try { token = await githubToken(); } catch (e) {
    for (const s of leased) await p.query("UPDATE cc_codespace_sessions SET lease_until = NULL, last_error = $2, next_check_at = now() + interval '5 minutes' WHERE id = $1", [s.id, (e as HttpError).code]);
    await oplog("scheduler", "ERROR", "Scheduler cannot read the GitHub token", { code: (e as HttpError).code });
    return { checked: leased.length, actions: ["NO_TOKEN"] };
  }
  for (const s of leased) {
    const deadlinePassed = new Date(s.deadline_at) <= now;
    try {
      const cs = await getCodespace(token, s.codespace_name);
      await p.query("UPDATE cc_codespace_sessions SET last_checked_at = now(), last_github_state = $2 WHERE id = $1", [s.id, cs.state]);
      if (STOPPED_STATES.has(cs.state)) {
        const final = s.state === "STOP_REQUESTED" ? "STOPPED" : "ENDED_EXTERNALLY";
        await closeSession(p, s.id, final, cs.state, final === "STOPPED" ? "DEADLINE_OR_MANUAL" : "STOPPED_BY_GITHUB");
        await audit(actor, "session.closed", "SUCCESS", s.codespace_name, { session: s.id, final });
        actions.push(`${s.id}:${final}`);
        continue;
      }
      if (cs.state === "Failed") {
        await closeSession(p, s.id, "FAILED", cs.state, "GITHUB_FAILED");
        await oplog("scheduler", "ERROR", `Codespace ${s.codespace_name} reported Failed`, { session: s.id });
        actions.push(`${s.id}:FAILED`);
        continue;
      }
      if (s.state === "STARTING" && RUNNING_STATES.has(cs.state)) {
        await p.query("UPDATE cc_codespace_sessions SET state = 'RUNNING', running_at = COALESCE(running_at, now()) WHERE id = $1", [s.id]);
        actions.push(`${s.id}:RUNNING`);
      }
      const stopStale = s.state === "STOP_REQUESTED" && s.stop_requested_at && now.getTime() - new Date(s.stop_requested_at).getTime() > 10 * 60_000;
      if ((deadlinePassed && s.state !== "STOP_REQUESTED") || (stopStale && cs.state !== "ShuttingDown")) {
        await stopCodespace(token, s.codespace_name);
        await p.query(`UPDATE cc_codespace_sessions SET state = 'STOP_REQUESTED', stop_reason = COALESCE(stop_reason, 'DEADLINE'), stop_requested_at = now(),
                         stop_attempts = stop_attempts + 1, last_error = NULL WHERE id = $1`, [s.id]);
        await audit(actor, "session.deadline_stop", "SUCCESS", s.codespace_name, { session: s.id, attempt: s.stop_attempts + 1 });
        actions.push(`${s.id}:STOP_REQUESTED`);
      }
      const next = deadlinePassed ? new Date(now.getTime() + 2 * 60_000) : new Date(Math.min(now.getTime() + 5 * 60_000, new Date(s.deadline_at).getTime()));
      await p.query("UPDATE cc_codespace_sessions SET next_check_at = $2, lease_until = NULL WHERE id = $1", [s.id, next]);
    } catch (e) {
      const code = e instanceof HttpError ? e.code : "UNEXPECTED";
      const attempts = s.stop_attempts + (deadlinePassed ? 1 : 0);
      const backoffMin = Math.min(30, 2 ** Math.min(attempts, 5));
      await p.query(`UPDATE cc_codespace_sessions SET last_error = $2, lease_until = NULL, stop_attempts = stop_attempts + $3,
                       next_check_at = now() + make_interval(mins => $4) WHERE id = $1`, [s.id, code, deadlinePassed ? 1 : 0, backoffMin]);
      const level = deadlinePassed && attempts >= 3 ? "ERROR" : "WARN";
      await oplog("scheduler", level, `${deadlinePassed ? "Deadline stop" : "Status check"} failed for ${s.codespace_name}: ${code}`, { session: s.id, attempts });
      if (deadlinePassed) await audit(actor, "session.deadline_stop", "FAILURE", s.codespace_name, { session: s.id, code, attempt: attempts });
      actions.push(`${s.id}:ERROR:${code}`);
    }
  }
  return { checked: leased.length, actions };
}

/** Live view used by the dashboard: GitHub state + open session; also runs a tick for the selected codespace. */
export async function overview() {
  const s = await settings();
  if (!s.selectedCodespace) return { configured: false as const, settings: s };
  await schedulerTick().catch(() => undefined); // confirms pending transitions sooner; safe to run concurrently
  let codespace: Codespace | null = null;
  let error: string | null = null;
  try {
    codespace = await getCodespace(await githubToken(), s.selectedCodespace);
  } catch (e) {
    error = e instanceof HttpError ? e.code : "UNEXPECTED";
  }
  return { configured: true as const, settings: s, codespace, error, session: await openSession(s.selectedCodespace), now: new Date().toISOString() };
}

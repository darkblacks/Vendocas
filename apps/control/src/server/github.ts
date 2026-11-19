import { env } from "./env";
import { HttpError, redact } from "./http";
import { outbound } from "./outbound";

/** Minimal GitHub REST client. Used only server-side with the token from the vault. */
export interface Codespace {
  name: string; displayName: string | null; state: string; repository: string; branch: string | null; machine: string | null;
  lastUsedAt: string | null; idleTimeoutMinutes: number | null; webUrl: string | null; retentionExpiresAt: string | null;
}

export class GitHubError extends HttpError {}

const headers = (token: string) => ({
  Authorization: `Bearer ${token}`, Accept: "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "vendocas-control",
});

async function gh(token: string, path: string, init: RequestInit = {}): Promise<Response> {
  let r: Response;
  try {
    r = await outbound(`${env.githubApiUrl()}${path}`, { ...init, headers: { ...headers(token), ...(init.headers ?? {}) } });
  } catch (e) {
    throw new GitHubError(503, "GITHUB_UNAVAILABLE", `GitHub API unreachable (${(e as Error).name})`);
  }
  if (r.ok) return r;
  const text = redact((await r.text().catch(() => "")).slice(0, 300));
  if (r.status === 401) throw new GitHubError(502, "GITHUB_AUTH_FAILED", "GitHub rejected the token (401). Replace it in Credentials.");
  if (r.status === 403) throw new GitHubError(502, "GITHUB_FORBIDDEN", `GitHub denied the operation (403). Check token permissions. ${text}`);
  if (r.status === 404) throw new GitHubError(404, "CODESPACE_NOT_FOUND", "Codespace or resource not found on GitHub");
  if (r.status === 409) throw new GitHubError(409, "GITHUB_CONFLICT", `GitHub reported a conflicting state. ${text}`);
  if (r.status === 429) throw new GitHubError(503, "GITHUB_RATE_LIMITED", "GitHub rate limit reached; retry later");
  throw new GitHubError(r.status >= 500 ? 503 : 502, "GITHUB_ERROR", `GitHub API error HTTP ${r.status}`);
}

const NAME = /^[A-Za-z0-9][A-Za-z0-9-]{1,98}$/;
export function assertCodespaceName(name: string) {
  if (!NAME.test(name)) throw new HttpError(400, "INVALID_CODESPACE", "Invalid codespace name");
}

function map(c: Record<string, any>): Codespace {
  return {
    name: c.name, displayName: c.display_name ?? null, state: c.state, repository: c.repository?.full_name ?? "", branch: c.git_status?.ref ?? null,
    machine: c.machine?.display_name ?? null, lastUsedAt: c.last_used_at ?? null, idleTimeoutMinutes: c.idle_timeout_minutes ?? null,
    webUrl: c.web_url ?? null, retentionExpiresAt: c.retention_expires_at ?? null,
  };
}

export async function listCodespaces(token: string): Promise<Codespace[]> {
  const r = await gh(token, "/user/codespaces?per_page=100");
  return (((await r.json()) as { codespaces?: unknown[] }).codespaces ?? []).map((c) => map(c as Record<string, any>));
}

export async function getCodespace(token: string, name: string): Promise<Codespace> {
  assertCodespaceName(name);
  return map((await (await gh(token, `/user/codespaces/${name}`)).json()) as Record<string, any>);
}

export async function startCodespace(token: string, name: string): Promise<Codespace> {
  assertCodespaceName(name);
  return map((await (await gh(token, `/user/codespaces/${name}/start`, { method: "POST" })).json()) as Record<string, any>);
}

export async function stopCodespace(token: string, name: string): Promise<Codespace> {
  assertCodespaceName(name);
  return map((await (await gh(token, `/user/codespaces/${name}/stop`, { method: "POST" })).json()) as Record<string, any>);
}

export async function whoAmI(token: string): Promise<string> {
  return (((await (await gh(token, "/user")).json()) as { login: string }).login);
}

export async function workflowRuns(token: string, repo: string) {
  const r = await gh(token, `/repos/${repo}/actions/runs?branch=main&per_page=15`);
  const runs = ((await r.json()) as { workflow_runs?: Record<string, any>[] }).workflow_runs ?? [];
  return runs.map((w) => ({ id: w.id, name: w.name, status: w.status, conclusion: w.conclusion, sha: w.head_sha, title: w.display_title,
    event: w.event, url: w.html_url, createdAt: w.created_at }));
}

export async function latestCommit(token: string, repo: string) {
  const c = (await (await gh(token, `/repos/${repo}/commits/main`)).json()) as Record<string, any>;
  return { sha: c.sha as string, message: String(c.commit?.message ?? "").split("\n")[0], date: c.commit?.committer?.date as string, url: c.html_url as string };
}

/** Billing usage is only exposed for some account types/token permissions; absence is reported, never invented. */
export async function codespacesUsage(token: string, login: string) {
  try {
    const r = await gh(token, `/users/${login}/settings/billing/usage`);
    const items = (((await r.json()) as { usageItems?: Record<string, any>[] }).usageItems ?? []).filter((i) => String(i.product).toLowerCase() === "codespaces");
    const sum = (unit: string) => items.filter((i) => String(i.unitType).toLowerCase().includes(unit)).reduce((a, i) => a + Number(i.quantity ?? 0), 0);
    return { available: true, coreHours: sum("hour"), storageGbMonths: sum("gigabyte"), grossAmount: items.reduce((a, i) => a + Number(i.grossAmount ?? 0), 0), items: items.length };
  } catch (e) {
    return { available: false, reason: e instanceof HttpError ? e.code : "UNAVAILABLE" };
  }
}

export const STOPPED_STATES = new Set(["Shutdown", "Archived", "Deleted"]);
export const RUNNING_STATES = new Set(["Available"]);
export const TRANSITIONAL_STATES = new Set(["Starting", "ShuttingDown", "Queued", "Provisioning", "Awaiting", "Rebuilding", "Updating", "Exporting", "Created"]);

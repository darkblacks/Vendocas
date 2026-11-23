import { PROBLEM_HINT, apiBase, isPrivateCodespaceRedirect, type UpstreamProblem } from "@/lib/server/upstream";

// Reachability probe for the Vendocas API (used by the "service unavailable" banner). Reports WHY the API is not
// reachable (configuration, stopped server, private Codespace port) without exposing the address itself.
export const dynamic = "force-dynamic";

export async function GET() {
  const no = (problem: UpstreamProblem) =>
    Response.json({ up: false, reason: problem, detail: PROBLEM_HINT[problem] }, { status: 503, headers: { "Cache-Control": "no-store" } });
  const base = apiBase();
  if ("problem" in base) return no(base.problem);
  let r: Response;
  try {
    r = await fetch(`${base.url}/actuator/health`, { signal: AbortSignal.timeout(4000), cache: "no-store", redirect: "manual" });
  } catch (e) {
    return no((e as Error).name === "TimeoutError" ? "API_TIMEOUT" : "API_UNREACHABLE");
  }
  if (isPrivateCodespaceRedirect(r)) return no("API_PRIVATE_PORT");
  const up = r.ok && ((await r.json().catch(() => ({}))) as { status?: string }).status === "UP";
  return up ? Response.json({ up: true }, { headers: { "Cache-Control": "no-store" } }) : no("API_UNHEALTHY");
}

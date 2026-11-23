// Server-only: where the Vendocas API lives, read at REQUEST time (not at build time), so changing API_INTERNAL_URL on
// Netlify takes effect without a rebuild, and a missing/wrong value produces a clear diagnosis instead of a bare 500.

export type UpstreamProblem = "API_NOT_CONFIGURED" | "API_INVALID_URL" | "API_UNREACHABLE" | "API_TIMEOUT" | "API_PRIVATE_PORT" | "API_UNHEALTHY";

export const PROBLEM_HINT: Record<UpstreamProblem, string> = {
  API_NOT_CONFIGURED: "API_INTERNAL_URL is not set for this site (Netlify → Site configuration → Environment variables).",
  API_INVALID_URL: "API_INTERNAL_URL is not a valid address. Expected something like https://<codespace>-8080.app.github.dev",
  API_UNREACHABLE: "The API host did not accept the connection (server or Codespace stopped, or wrong address).",
  API_TIMEOUT: "The API did not answer in time (it may be starting).",
  API_PRIVATE_PORT: "The API is a GitHub Codespace port set to private: make port 8080 public or use another host.",
  API_UNHEALTHY: "The API answered but reports itself as not healthy.",
};

/**
 * Normalizes what people commonly paste into the Netlify field: surrounding quotes/angle brackets/whitespace, a missing
 * "https://", a trailing slash or a trailing "/api/v1" or "/actuator/health". Returns null when it still is not a URL.
 */
export function normalizeApiUrl(raw: string): string | null {
  let v = raw.trim().replace(/^[\s"'<`]+|[\s"'>`]+$/g, "").trim();
  if (!v) return null;
  if (!/^[a-z][a-z0-9+.-]*:\/\//i.test(v)) v = `https://${v}`;
  try {
    const u = new URL(v);
    if (u.protocol !== "http:" && u.protocol !== "https:") return null;
    if (!u.hostname || /\s/.test(u.hostname) || !(u.hostname.includes(".") || u.hostname === "localhost")) return null;
    const path = u.pathname.replace(/\/+$/, "").replace(/\/(api\/v1|actuator\/health)$/i, "");
    return u.origin + path;
  } catch { return null; }
}

export function apiBase(): { url: string } | { problem: UpstreamProblem } {
  const raw = process.env.API_INTERNAL_URL;
  if (!raw?.trim()) return process.env.NODE_ENV === "production" ? { problem: "API_NOT_CONFIGURED" } : { url: "http://localhost:8080" };
  const url = normalizeApiUrl(raw);
  return url ? { url } : { problem: "API_INVALID_URL" };
}

/** A private Codespace port answers with a redirect to the GitHub sign-in page instead of the API. */
export function isPrivateCodespaceRedirect(r: Response): boolean {
  if (r.status < 300 || r.status >= 400) return false;
  const loc = r.headers.get("location") ?? "";
  return /github\.com\/login|github\.dev\/pf-signin|app\.github\.dev\/auth/i.test(loc);
}

export function problemResponse(problem: UpstreamProblem): Response {
  // 502 + JSON: the browser client treats it as "service unavailable" (banner + backoff), never as a logout.
  return Response.json({ code: problem, detail: PROBLEM_HINT[problem] }, { status: 502, headers: { "Cache-Control": "no-store" } });
}

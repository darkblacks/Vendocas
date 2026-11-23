import { apiBase, isPrivateCodespaceRedirect, problemResponse } from "@/lib/server/upstream";

/**
 * Same-origin proxy to the Vendocas API (/api/v1/* → API_INTERNAL_URL/api/v1/*). Replaces the build-time rewrite:
 * the target is read on every request. Body bytes, headers and cookies pass through unchanged (raw body keeps
 * webhook signatures valid; Set-Cookie keeps the refresh cookie first-party on this domain).
 */
export const dynamic = "force-dynamic";

const HOP_BY_HOP = new Set(["connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length"]);

async function proxy(req: Request, ctx: { params: Promise<{ path: string[] }> }): Promise<Response> {
  const base = apiBase();
  if ("problem" in base) return problemResponse(base.problem);
  const { path } = await ctx.params;
  const incoming = new URL(req.url);
  const target = `${base.url}/api/v1/${path.map(encodeURIComponent).join("/")}${incoming.search}`;

  const headers = new Headers();
  req.headers.forEach((v, k) => { if (!HOP_BY_HOP.has(k.toLowerCase())) headers.set(k, v); });
  // A request from this site's own pages is same-origin for the browser and server-to-server from here on: drop its
  // Origin so the API's CORS filter (allow-list for direct cross-origin calls) does not reject it with 403. Requests
  // from other sites (e.g. the tracking script on merchants' stores) keep their Origin and get the API's CORS rules.
  const origin = req.headers.get("origin");
  if (origin && origin === incoming.origin) headers.delete("origin");
  const ip = req.headers.get("x-nf-client-connection-ip") ?? req.headers.get("x-forwarded-for")?.split(",")[0]?.trim();
  if (ip) headers.set("x-forwarded-for", ip);
  headers.set("x-forwarded-host", incoming.host);
  headers.set("x-forwarded-proto", incoming.protocol.replace(":", ""));

  let upstream: Response;
  try {
    upstream = await fetch(target, {
      method: req.method, headers, redirect: "manual", cache: "no-store",
      body: ["GET", "HEAD"].includes(req.method) ? undefined : await req.arrayBuffer(),
      signal: AbortSignal.timeout(20_000),
    });
  } catch (e) {
    return problemResponse((e as Error).name === "TimeoutError" ? "API_TIMEOUT" : "API_UNREACHABLE");
  }
  if (isPrivateCodespaceRedirect(upstream)) return problemResponse("API_PRIVATE_PORT");

  const out = new Headers();
  upstream.headers.forEach((v, k) => { if (!HOP_BY_HOP.has(k.toLowerCase()) && k.toLowerCase() !== "set-cookie" && k.toLowerCase() !== "content-encoding") out.set(k, v); });
  for (const c of upstream.headers.getSetCookie()) out.append("set-cookie", c);
  return new Response(upstream.body, { status: upstream.status, statusText: upstream.statusText, headers: out });
}

export const GET = proxy;
export const POST = proxy;
export const PUT = proxy;
export const PATCH = proxy;
export const DELETE = proxy;
export const HEAD = proxy;
export const OPTIONS = proxy;

import { backendStatus, isUnavailableResponse } from "./backend-status";

/**
 * Browser API access. The access token lives only in memory; the refresh token is an httpOnly,
 * SameSite=Strict cookie scoped to /api/v1/auth (same origin thanks to the Next rewrite).
 */
export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public body: Record<string, unknown> = {}) {
    super(message);
  }
  get fields(): Record<string, string> { return (this.body.fields as Record<string, string>) ?? {}; }
  get errors(): string[] { return (this.body.errors as string[]) ?? []; }
}

let accessToken: string | null = null;
let organizationId: string | null = null;
let refreshing: Promise<string | null> | null = null;

export const session = {
  setToken(t: string | null) { accessToken = t; },
  getToken() { return accessToken; },
  setOrg(id: string | null) {
    organizationId = id;
    try { if (id) localStorage.setItem("vd_org", id); else localStorage.removeItem("vd_org"); } catch { /* storage unavailable */ }
  },
  getOrg() { return organizationId; },
  savedOrg(): string | null { try { return localStorage.getItem("vd_org"); } catch { return null; } },
};

export const UNAVAILABLE = "BACKEND_UNAVAILABLE";
const UNAVAILABLE_TEXT: Record<string, string> = {
  pt: "Serviço temporariamente indisponível. Tente novamente em instantes.",
  es: "Servicio temporalmente no disponible. Inténtalo de nuevo en unos instantes.",
  en: "Service temporarily unavailable. Please try again shortly.",
};
const pageLang = () => (typeof document !== "undefined" ? document.documentElement.lang.slice(0, 2) : "en");
const unavailable = () => new ApiError(503, UNAVAILABLE, UNAVAILABLE_TEXT[pageLang()] ?? UNAVAILABLE_TEXT.en!);

export async function refreshAccessToken(): Promise<string | null> {
  if (!backendStatus.get().up) return null;
  if (!refreshing) {
    refreshing = fetch("/api/v1/auth/refresh", { method: "POST", credentials: "include", headers: { "X-Requested-With": "XMLHttpRequest" } })
      .then(async (r) => (r.ok ? ((await r.json()) as { accessToken: string }).accessToken : null))
      .catch(() => null)
      .then((t) => { accessToken = t; refreshing = null; return t; });
  }
  return refreshing;
}

type Options = { method?: string; body?: unknown; form?: FormData; tenant?: boolean; retry?: boolean };

export async function api<T = unknown>(path: string, opts: Options = {}): Promise<T> {
  const headers: Record<string, string> = {};
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`;
  if (opts.tenant !== false && organizationId) headers["X-Organization-Id"] = organizationId;
  if (opts.body !== undefined) headers["Content-Type"] = "application/json";
  // Fail fast while the API is known to be down (e.g. homologation Codespace switched off): no request loops.
  if (!backendStatus.get().up) throw unavailable();
  let res: Response;
  try {
    res = await fetch(`/api/v1${path}`, {
      method: opts.method ?? (opts.body !== undefined || opts.form ? "POST" : "GET"),
      headers, credentials: "include",
      body: opts.form ?? (opts.body !== undefined ? JSON.stringify(opts.body) : undefined),
    });
  } catch {
    backendStatus.markDown();
    throw unavailable();
  }
  if (isUnavailableResponse(res)) {
    backendStatus.markDown();
    throw unavailable();
  }
  if (res.status === 401 && opts.retry !== false && !path.startsWith("/auth/login")) {
    const t = await refreshAccessToken();
    if (t) return api<T>(path, { ...opts, retry: false });
  }
  if (res.status === 204 || res.status === 202 && res.headers.get("content-length") === "0") return undefined as T;
  const text = await res.text();
  let data: unknown;
  try { data = text ? JSON.parse(text) : undefined; } catch { data = undefined; }
  if (!res.ok) {
    const d = (data ?? {}) as Record<string, unknown>;
    throw new ApiError(res.status, String(d.code ?? "ERROR"), String(d.detail ?? res.statusText), d);
  }
  return data as T;
}

export function errorMessage(e: unknown): string {
  if (e instanceof ApiError) {
    const extra = e.errors.length ? ` — ${e.errors.join("; ")}` : "";
    return e.message + extra;
  }
  return e instanceof Error ? e.message : String(e);
}

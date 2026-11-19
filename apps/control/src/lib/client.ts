"use client";

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public body: Record<string, unknown> = {}) { super(message); }
}

let csrf: string | null = null;
export const setCsrf = (t: string | null) => { csrf = t; };

type ReauthHandler = () => Promise<boolean>;
let reauthHandler: ReauthHandler | null = null;
export const onReauthRequired = (h: ReauthHandler | null) => { reauthHandler = h; };

/** Same-origin JSON calls. Mutations carry the session-bound CSRF token; a REAUTH_REQUIRED answer opens the re-auth dialog and retries once. */
export async function call<T = unknown>(path: string, init: { method?: string; body?: unknown; headers?: Record<string, string> } = {}, retried = false): Promise<T> {
  const method = init.method ?? (init.body !== undefined ? "POST" : "GET");
  if (method !== "GET" && !csrf) {
    const me = await fetch("/api/auth/me", { credentials: "same-origin" });
    if (me.ok) csrf = ((await me.json()) as { csrfToken: string }).csrfToken;
  }
  const res = await fetch(path, {
    method, credentials: "same-origin",
    headers: { ...(init.body !== undefined ? { "Content-Type": "application/json" } : {}), ...(method !== "GET" && csrf ? { "X-CSRF-Token": csrf } : {}), ...init.headers },
    body: init.body !== undefined ? JSON.stringify(init.body) : undefined,
  });
  const text = await res.text();
  const data = text ? JSON.parse(text) : {};
  if (res.ok) return data as T;
  if (res.status === 401 && typeof window !== "undefined" && !path.startsWith("/api/auth")) window.location.href = "/login";
  if (res.status === 403 && data.code === "REAUTH_REQUIRED" && !retried && reauthHandler && (await reauthHandler())) return call<T>(path, init, true);
  throw new ApiError(res.status, data.code ?? "ERROR", data.message ?? res.statusText, data);
}

export const message = (e: unknown) => (e instanceof Error ? e.message : String(e));

import { ConfigError } from "./env";

export class HttpError extends Error {
  constructor(public status: number, public code: string, message: string, public extra: Record<string, unknown> = {}) {
    super(message);
  }
}

export const deny = (code = "FORBIDDEN", msg = "Not allowed") => new HttpError(403, code, msg);
export const bad = (msg: string, extra: Record<string, unknown> = {}) => new HttpError(400, "BAD_REQUEST", msg, extra);

/** Redacts anything that looks like a credential before it can reach a response or a log line. */
export function redact(text: string): string {
  return text
    .replace(/(sk|rk|pk)_(live|test)_[A-Za-z0-9]+/g, "$1_$2_[REDACTED]")
    .replace(/gh[pousr]_[A-Za-z0-9]{20,}/g, "gh_[REDACTED]")
    .replace(/github_pat_[A-Za-z0-9_]{20,}/g, "github_pat_[REDACTED]")
    .replace(/EAA[A-Za-z0-9]{20,}/g, "EAA[REDACTED]")
    .replace(/AKIA[0-9A-Z]{16}/g, "AKIA[REDACTED]")
    .replace(/(Bearer|Basic|token)\s+[A-Za-z0-9._\-+/=]{12,}/gi, "$1 [REDACTED]");
}

export function json(data: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store", ...headers },
  });
}

/** Wraps a route handler: maps known errors to JSON, never leaks stack traces or secrets. */
export function handle<A extends unknown[]>(fn: (...args: A) => Promise<Response>) {
  return async (...args: A): Promise<Response> => {
    try {
      return await fn(...args);
    } catch (e) {
      if (e instanceof HttpError) return json({ code: e.code, message: redact(e.message), ...e.extra }, e.status);
      if (e instanceof ConfigError) return json({ code: e.code, message: e.message }, 503);
      console.error("[control] unhandled", redact(e instanceof Error ? `${e.name}: ${e.message}` : String(e)));
      return json({ code: "INTERNAL", message: "Unexpected error" }, 500);
    }
  };
}

export async function body<T>(req: Request, parse: (v: unknown) => T): Promise<T> {
  let raw: unknown;
  try { raw = await req.json(); } catch { throw bad("Malformed JSON body"); }
  try { return parse(raw); } catch (e) {
    throw bad("Invalid input", { issues: (e as { issues?: unknown }).issues ?? undefined });
  }
}

export function clientIp(req: Request): string {
  // Netlify sets x-nf-client-connection-ip; fall back to the first X-Forwarded-For hop.
  return req.headers.get("x-nf-client-connection-ip") ?? req.headers.get("x-forwarded-for")?.split(",")[0]?.trim() ?? "unknown";
}

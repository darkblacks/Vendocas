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
    .replace(/(Bearer|Basic|token)\s+[A-Za-z0-9._\-+/=]{12,}/gi, "$1 [REDACTED]")
    .replace(/postgres(ql)?:\/\/[^\s"'`]+/gi, "postgres://[REDACTED]");
}

export function json(data: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store", ...headers },
  });
}

/** JSON response that sets several cookies (session + trusted device, rotation, …). */
export function jsonWithCookies(data: unknown, cookies: string[], status = 200): Response {
  const headers = new Headers({ "Content-Type": "application/json", "Cache-Control": "no-store" });
  for (const c of cookies) headers.append("Set-Cookie", c);
  return new Response(JSON.stringify(data), { status, headers });
}

/** Every API answer must arrive well before the platform's gateway timeout, and always as JSON. */
export const HANDLER_DEADLINE_MS = 9_500;

/** Wraps a route handler: maps known errors to JSON, never leaks stack traces or secrets, enforces a deadline. */
export function handle<A extends unknown[]>(fn: (...args: A) => Promise<Response>, deadlineMs = HANDLER_DEADLINE_MS) {
  return async (...args: A): Promise<Response> => {
    let timer: ReturnType<typeof setTimeout> | undefined;
    try {
      const work = fn(...args);
      work.catch(() => undefined); // if the deadline wins, a later rejection must not become unhandled
      return await Promise.race([work, new Promise<never>((_, rej) => {
        timer = setTimeout(() => rej(new HttpError(503, "OPERATION_TIMEOUT", "The operation took too long; please try again")), deadlineMs);
      })]);
    } catch (e) {
      if (e instanceof HttpError) return json({ code: e.code, message: redact(e.message), ...e.extra }, e.status);
      if (e instanceof ConfigError) return json({ code: e.code, message: e.message }, 503);
      console.error("[control] unhandled", redact(e instanceof Error ? `${e.name}: ${e.message}` : String(e)));
      return json({ code: "INTERNAL", message: "Unexpected error" }, 500);
    } finally {
      clearTimeout(timer);
    }
  };
}

export async function body<T>(req: Request, parse: (v: unknown) => T): Promise<T> {
  let raw: unknown;
  try { raw = await req.json(); } catch { throw bad("Malformed JSON body"); }
  try { return parse(raw); } catch (e) {
    // Only path/code/message: never echo submitted values (they may be secrets).
    const issues = (e as { issues?: { path?: unknown; code?: unknown; message?: unknown }[] }).issues;
    throw bad("Invalid input", { issues: issues?.map((i) => ({ path: i.path, code: i.code, message: i.message })) });
  }
}

export function clientIp(req: Request): string {
  // Netlify sets x-nf-client-connection-ip; fall back to the first X-Forwarded-For hop.
  return req.headers.get("x-nf-client-connection-ip") ?? req.headers.get("x-forwarded-for")?.split(",")[0]?.trim() ?? "unknown";
}

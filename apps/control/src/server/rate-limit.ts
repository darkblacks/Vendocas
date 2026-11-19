import { db } from "./db";
import { HttpError } from "./http";

/** Fixed-window limiter persisted in Postgres (serverless instances share no memory). */
export async function rateLimit(bucket: string, limit: number, windowSeconds: number): Promise<void> {
  const p = await db();
  const r = await p.query<{ hits: number }>(`
    INSERT INTO cc_rate_limits (bucket, window_start, hits)
    VALUES ($1, to_timestamp(floor(extract(epoch FROM now()) / $2) * $2), 1)
    ON CONFLICT (bucket, window_start) DO UPDATE SET hits = cc_rate_limits.hits + 1
    RETURNING hits`, [bucket, windowSeconds]);
  if (Math.random() < 0.02) await p.query("DELETE FROM cc_rate_limits WHERE window_start < now() - interval '1 day'");
  if ((r.rows[0]?.hits ?? 0) > limit) throw new HttpError(429, "RATE_LIMITED", "Too many requests, try again later", { retryAfterSeconds: windowSeconds });
}

/**
 * Per-instance limiter for steps that run before a database exists (installation). Serverless instances share no
 * memory, so install.ts additionally keeps a best-effort counter in the external config store; the installation key's
 * entropy (>= 32 characters) is what actually makes guessing infeasible.
 */
const memoryHits = new Map<string, { start: number; hits: number }>();
export function memoryRateLimit(bucket: string, limit: number, windowMs: number) {
  const now = Date.now();
  const e = memoryHits.get(bucket);
  if (!e || now - e.start > windowMs) { memoryHits.set(bucket, { start: now, hits: 1 }); return; }
  if (++e.hits > limit) throw new HttpError(429, "RATE_LIMITED", "Too many attempts, try again later", { retryAfterSeconds: Math.ceil((e.start + windowMs - now) / 1000) });
}
export function __resetMemoryRateLimits() { memoryHits.clear(); }

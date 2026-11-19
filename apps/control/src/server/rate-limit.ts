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

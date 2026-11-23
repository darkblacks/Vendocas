import { HttpError, redact } from "./http";
import { outbound } from "./outbound";
import { reveal } from "./vault";
import { db } from "./db";

/**
 * Health of the Vendocas API running in the Codespace, read from its token-protected /internal/status endpoint.
 * When the codespace is off or the endpoint is unreachable the result says so — nothing is assumed healthy.
 */
export interface ApiStatus {
  reachable: boolean;
  reason?: string;
  checkedAt: string;
  data?: {
    status: string; gitCommit: string | null; startedAt: string | null; database: { up: boolean; latencyMs?: number; flywayVersion?: string | null };
    redis: { up: boolean; error?: string }; workers: { enabled: boolean; outboxPending: number; jobsPending: number; jobsDead: number;
    messagesQueued: number; automationDue: number; deadLetters: number }; recentErrors: { at: string; source: string; message: string }[];
    platform?: PlatformStatus | null; settingsWritable?: boolean;
  };
}

/** Environment / payments / fiscal switches as reported by the API (never contains keys). */
export interface PlatformStatus {
  settings: { environment: "HOMOLOGATION" | "PRODUCTION"; stripeMode: "TEST" | "LIVE"; fiscalAutomation: boolean; fiscalProvider: "NONE" | "FOCUS_NFE"; version: number; updatedBy: string | null; updatedAt: string };
  stripeKeyMode: string; stripeConfigured: boolean; stripeWebhookConfigured: boolean; stripeModeMatches: boolean; focusConfigured: boolean;
  focusEnvironment: string | null; fiscalMode: string; fiscalDocuments: Record<string, number>;
  services: { key: string; label: string; state: string; detail: string | null }[]; warnings: string[];
}

/** target TEST = homologation API (Codespace), LIVE = production API. */
export async function apiStatus(target: "TEST" | "LIVE" = "TEST"): Promise<ApiStatus> {
  const checkedAt = new Date().toISOString();
  let cfg: Record<string, string>;
  try {
    cfg = await reveal("VENDOCAS_API", target);
  } catch (e) {
    return { reachable: false, reason: e instanceof HttpError ? e.code : "NOT_CONFIGURED", checkedAt };
  }
  let gh: string | null = null;
  try { gh = (await reveal("GITHUB", "LIVE")).token ?? null; } catch { /* optional: only needed for private forwarded ports */ }
  try {
    const r = await outbound(`${cfg.baseUrl}/internal/status`, {
      headers: { "X-Control-Token": cfg.statusToken!, ...(gh ? { "X-Github-Token": gh } : {}) }, signal: AbortSignal.timeout(6000),
    });
    if (!r.ok) return { reachable: false, reason: `HTTP_${r.status}`, checkedAt };
    const data = (await r.json()) as ApiStatus["data"];
    if (data?.recentErrors) data.recentErrors = data.recentErrors.map((e) => ({ ...e, message: redact(e.message) }));
    return { reachable: true, checkedAt, data };
  } catch (e) {
    return { reachable: false, reason: (e as Error).name === "TimeoutError" ? "TIMEOUT" : "UNREACHABLE", checkedAt };
  }
}

export async function recentLogs(limit = 100) {
  const p = await db();
  return (await p.query(`SELECT category, level, message, detail, created_at AS "createdAt" FROM cc_operation_log ORDER BY created_at DESC LIMIT $1`, [limit])).rows;
}

export async function auditEvents(limit = 200) {
  const p = await db();
  return (await p.query(`SELECT id, actor_label AS "actor", action, target, outcome, detail, ip, created_at AS "createdAt" FROM cc_audit_events
                         ORDER BY created_at DESC LIMIT $1`, [limit])).rows;
}

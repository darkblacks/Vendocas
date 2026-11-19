import type pg from "pg";
import { db } from "./db";
import { redact } from "./http";

export interface Actor { id: string | null; label: string; ip?: string }
export const SYSTEM: Actor = { id: null, label: "system:scheduler" };

export async function audit(actor: Actor, action: string, outcome: "SUCCESS" | "FAILURE" | "DENIED", target?: string | null,
                            detail: Record<string, unknown> = {}, client?: pg.PoolClient): Promise<void> {
  const q = client ?? (await db());
  await q.query("INSERT INTO cc_audit_events (actor_id, actor_label, action, target, outcome, detail, ip) VALUES ($1,$2,$3,$4,$5,$6::jsonb,$7)",
    [actor.id, actor.label, action, target ?? null, outcome, redact(JSON.stringify(detail)), actor.ip ?? null]);
}

export async function oplog(category: string, level: "INFO" | "WARN" | "ERROR", message: string, detail: Record<string, unknown> = {}): Promise<void> {
  const p = await db();
  await p.query("INSERT INTO cc_operation_log (category, level, message, detail) VALUES ($1,$2,$3,$4::jsonb)",
    [category, level, redact(message), redact(JSON.stringify(detail))]);
}

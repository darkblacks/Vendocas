import { audit } from "./audit";
import type { AuthContext } from "./auth";
import { HttpError, redact } from "./http";
import { outbound } from "./outbound";
import { apiStatus, type PlatformStatus } from "./status";
import { reveal } from "./vault";

/**
 * Environment, Stripe mode and fiscal issuance of each Vendocas API (TEST = homologation, LIVE = production).
 * The API is the source of truth (table platform_settings); the Control Center reads it with the read-only status
 * token and changes it with the separate CONTROL_ADMIN_TOKEN, sending the acting administrator for the API audit log.
 */
export type Target = "TEST" | "LIVE";

export interface TargetView {
  target: Target; configured: boolean; reachable: boolean; reason?: string; writable: boolean; adminTokenStored: boolean;
  platform: PlatformStatus | null; checkedAt: string;
}

export async function overview(): Promise<TargetView[]> {
  return Promise.all((["TEST", "LIVE"] as Target[]).map(async (target) => {
    let creds: Record<string, string> | null = null;
    try { creds = await reveal("VENDOCAS_API", target); } catch { /* not registered */ }
    const st = await apiStatus(target);
    return {
      target, configured: !!creds, reachable: st.reachable, reason: st.reason, checkedAt: st.checkedAt,
      adminTokenStored: !!creds?.adminToken, writable: !!creds?.adminToken && !!st.data?.settingsWritable,
      platform: st.data?.platform ?? null,
    };
  }));
}

export interface Change {
  environment?: "HOMOLOGATION" | "PRODUCTION"; stripeMode?: "TEST" | "LIVE"; fiscalAutomation?: boolean; fiscalProvider?: "NONE" | "FOCUS_NFE";
  expectedVersion?: number;
}

export async function update(ctx: AuthContext, target: Target, change: Change): Promise<PlatformStatus> {
  let creds: Record<string, string>;
  try { creds = await reveal("VENDOCAS_API", target); } catch {
    throw new HttpError(409, "API_NOT_CONFIGURED", "Cadastre a API Vendocas deste ambiente em Credenciais");
  }
  if (!creds.adminToken) throw new HttpError(409, "ADMIN_TOKEN_NOT_CONFIGURED", "Cadastre o CONTROL_ADMIN_TOKEN da API em Credenciais para alterar as configurações");
  let gh: string | null = null;
  try { gh = (await reveal("GITHUB", "LIVE")).token ?? null; } catch { /* only needed for private Codespace ports */ }
  let r: Response;
  try {
    r = await outbound(`${creds.baseUrl}/internal/platform-settings`, {
      method: "PUT",
      headers: { "Content-Type": "application/json", "X-Control-Admin-Token": creds.adminToken, "X-Control-Actor": ctx.user.email,
        ...(target === "TEST" && gh ? { "X-Github-Token": gh } : {}) },
      body: JSON.stringify(change), signal: AbortSignal.timeout(8000),
    });
  } catch (e) {
    await audit(ctx.actor, "platform.settings_update", "FAILURE", target, { reason: "unreachable" });
    throw new HttpError(503, "API_UNREACHABLE", (e as Error).name === "TimeoutError" ? "A API não respondeu a tempo" : "A API está inacessível");
  }
  if (!r.ok) {
    const code = r.status === 401 ? "ADMIN_TOKEN_REJECTED" : r.status === 404 ? "ADMIN_TOKEN_NOT_ENABLED" : r.status === 409 ? "SETTINGS_CONFLICT" : "API_ERROR";
    const detail = redact((await r.text().catch(() => "")).slice(0, 300));
    await audit(ctx.actor, "platform.settings_update", "FAILURE", target, { code, status: r.status });
    throw new HttpError(r.status === 409 ? 409 : 502, code, code === "SETTINGS_CONFLICT" ? "As configurações foram alteradas por outra pessoa; recarregue e tente de novo"
      : code === "ADMIN_TOKEN_REJECTED" ? "A API recusou o CONTROL_ADMIN_TOKEN cadastrado"
      : code === "ADMIN_TOKEN_NOT_ENABLED" ? "A API não tem CONTROL_ADMIN_TOKEN configurado" : `Erro da API (HTTP ${r.status}) ${detail}`.trim());
  }
  const status = (await r.json()) as PlatformStatus;
  await audit(ctx.actor, "platform.settings_update", "SUCCESS", target, { ...change, version: status.settings.version });
  return status;
}

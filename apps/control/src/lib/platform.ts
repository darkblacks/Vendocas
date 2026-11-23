// Shared client types/labels for the environment, payments and fiscal settings.
export interface PlatformStatus {
  settings: { environment: "HOMOLOGATION" | "PRODUCTION"; stripeMode: "TEST" | "LIVE"; fiscalAutomation: boolean; fiscalProvider: "NONE" | "FOCUS_NFE"; version: number; updatedBy: string | null; updatedAt: string };
  stripeKeyMode: string; stripeConfigured: boolean; stripeModeMatches: boolean; focusConfigured: boolean; focusEnvironment: string | null; fiscalMode: string;
  fiscalDocuments: Record<string, number>; services: { key: string; label: string; state: string; detail: string | null }[]; warnings: string[];
}
export interface TargetView { target: "TEST" | "LIVE"; configured: boolean; reachable: boolean; reason?: string; writable: boolean; adminTokenStored: boolean; platform: PlatformStatus | null; checkedAt: string }

export const TARGET_LABEL = { TEST: "API de homologação (Codespace)", LIVE: "API de produção" } as const;

export const STATE_LABEL: Record<string, string> = {
  ACTIVE: "ativo", DISABLED: "desativado", NOT_CONFIGURED: "não configurado", NONE: "nenhum", CONFIGURED: "configurado",
  MISCONFIGURED: "configuração incompleta", BLOCKED_MODE_MISMATCH: "bloqueado (modo ≠ chave)", HOMOLOGATION: "homologação", PRODUCTION: "produção",
};

const FISCAL_MODE_LABEL: Record<string, string> = {
  MANUAL: "emissão manual", AUTOMATIC: "automática", AUTOMATIC_WITHOUT_PROVIDER: "ativada sem provedor", AUTOMATIC_PROVIDER_NOT_CONFIGURED: "provedor sem credencial",
};
/** Human-readable detail for a service row (the API sends codes). */
export function serviceDetail(key: string, detail: string | null): string | null {
  if (!detail) return null;
  if (key === "fiscal_automation") return FISCAL_MODE_LABEL[detail] ?? detail;
  if (key === "stripe") return detail.replace(/modo (TEST|LIVE)/, (_, m) => `modo ${m === "LIVE" ? "produção" : "teste"}`)
    .replace(/chave (TEST|LIVE|UNKNOWN)/, (_, m) => `chave de ${m === "LIVE" ? "produção" : m === "TEST" ? "teste" : "tipo desconhecido"}`);
  return detail.replace("(HOMOLOGATION)", "(homologação)").replace("(PRODUCTION)", "(produção)");
}

export function serviceTone(state: string): "ok" | "warn" | "bad" | "neutral" | "info" {
  if (["ACTIVE", "CONFIGURED"].includes(state)) return "ok";
  if (["BLOCKED_MODE_MISMATCH"].includes(state)) return "bad";
  if (["MISCONFIGURED", "NOT_CONFIGURED"].includes(state)) return "warn";
  if (["PRODUCTION"].includes(state)) return "info";
  return "neutral";
}

export const FISCAL_DOC_LABEL: Record<string, string> = {
  AWAITING_MANUAL: "aguardando emissão manual", PENDING: "na fila", PROCESSING: "processando", ISSUED: "emitidas", REJECTED: "rejeitadas",
  NOT_CONFIGURED: "sem provedor", CANCELED: "canceladas",
};

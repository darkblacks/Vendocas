import { audit } from "./audit";
import type { AuthContext } from "./auth";
import { HttpError, redact } from "./http";
import { outbound } from "./outbound";
import { reveal } from "./vault";
import type { Target } from "./platform";

/**
 * Control Center → Vendocas API, commercial side: markets, plan prices per market, included quotas and the merchants
 * (the operator's CRM). Every call uses the API's CONTROL_ADMIN_TOKEN from the vault, server-side only; the browser never
 * sees it. Merchant data is tenant data, so even reads need the admin token (the status token is not enough).
 */
async function adminCall<T>(ctx: AuthContext, target: Target, method: "GET" | "PUT", path: string, body?: unknown, action?: string): Promise<T> {
  let creds: Record<string, string>;
  try { creds = await reveal("VENDOCAS_API", target); } catch {
    throw new HttpError(409, "API_NOT_CONFIGURED", "Cadastre a API Vendocas deste ambiente em Credenciais");
  }
  if (!creds.adminToken) throw new HttpError(409, "ADMIN_TOKEN_NOT_CONFIGURED", "Cadastre o CONTROL_ADMIN_TOKEN da API em Credenciais");
  let gh: string | null = null;
  try { gh = (await reveal("GITHUB", "LIVE")).token ?? null; } catch { /* only needed for private Codespace ports */ }
  let r: Response;
  try {
    r = await outbound(`${creds.baseUrl}${path}`, {
      method,
      headers: {
        "X-Control-Admin-Token": creds.adminToken, "X-Control-Actor": ctx.user.email,
        ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
        ...(target === "TEST" && gh ? { "X-Github-Token": gh } : {}),
      },
      body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(8000),
    });
  } catch (e) {
    if (action) await audit(ctx.actor, action, "FAILURE", target, { reason: "unreachable" });
    throw new HttpError(503, "API_UNREACHABLE", (e as Error).name === "TimeoutError" ? "A API não respondeu a tempo" : "A API está inacessível");
  }
  if (!r.ok) {
    const text = redact((await r.text().catch(() => "")).slice(0, 400));
    let detail = "";
    try { detail = String((JSON.parse(text) as { detail?: string }).detail ?? ""); } catch { /* not JSON */ }
    const code = r.status === 401 ? "ADMIN_TOKEN_REJECTED" : r.status === 404 && !detail ? "ADMIN_TOKEN_NOT_ENABLED" : r.status === 409 ? "CONFLICT"
      : r.status === 400 ? "INVALID" : r.status === 404 ? "NOT_FOUND" : "API_ERROR";
    if (action) await audit(ctx.actor, action, "FAILURE", target, { code, status: r.status });
    const msg = code === "ADMIN_TOKEN_REJECTED" ? "A API recusou o CONTROL_ADMIN_TOKEN cadastrado"
      : code === "ADMIN_TOKEN_NOT_ENABLED" ? "A API não tem CONTROL_ADMIN_TOKEN configurado (ou é uma versão sem estas telas)"
      : detail || `Erro da API (HTTP ${r.status})`;
    throw new HttpError(code === "CONFLICT" ? 409 : code === "INVALID" ? 400 : code === "NOT_FOUND" ? 404 : 502, code, msg);
  }
  return (await r.json()) as T;
}

const MARKETS = new Set(["BR", "LATAM", "NA", "EU"]);
const PLANS = new Set(["ALERTA", "ALERTA_VENDAS", "ILIMITADO"]);
const CHANNELS = new Set(["EMAIL", "WHATSAPP", "SMS"]);
function check(set: Set<string>, v: string, what: string) {
  if (!set.has(v)) throw new HttpError(400, "BAD_REQUEST", `${what} inválido`);
}

export const pricing = (ctx: AuthContext, target: Target) => adminCall<unknown[]>(ctx, target, "GET", "/internal/commercial/pricing");
export const markets = (ctx: AuthContext, target: Target) => adminCall<unknown[]>(ctx, target, "GET", "/internal/commercial/markets");

export interface MarketChange {
  name?: string; defaultLocale?: string; locales?: string[]; channelOrder?: string[]; sendWindowStart?: string; sendWindowEnd?: string;
  enabled?: boolean; expectedVersion?: number;
}

export async function updateMarket(ctx: AuthContext, target: Target, market: string, change: MarketChange) {
  check(MARKETS, market, "Mercado");
  const r = await adminCall<{ version: number }>(ctx, target, "PUT", `/internal/commercial/markets/${market}`, change, "commercial.market_update");
  await audit(ctx.actor, "commercial.market_update", "SUCCESS", target, { market, ...change });
  return r;
}

export interface PriceChange { unitAmountMinor: number; stripePriceId?: string | null; offerText?: string | null; trialDays?: number | null }

export async function setPrice(ctx: AuthContext, target: Target, market: string, plan: string, change: PriceChange) {
  check(MARKETS, market, "Mercado");
  check(PLANS, plan, "Plano");
  const r = await adminCall<unknown>(ctx, target, "PUT", `/internal/commercial/prices/${market}/${plan}`, change, "commercial.price_set");
  await audit(ctx.actor, "commercial.price_set", "SUCCESS", target, { market, plan, unitAmountMinor: change.unitAmountMinor, stripeLinked: !!change.stripePriceId });
  return r;
}

export interface QuotaChange { monthlyLimit: number | null; fairUseNote?: string | null; remove?: boolean }

export async function setQuota(ctx: AuthContext, target: Target, market: string, plan: string, channel: string, change: QuotaChange) {
  check(MARKETS, market, "Mercado");
  check(PLANS, plan, "Plano");
  check(CHANNELS, channel, "Canal");
  const r = await adminCall<unknown>(ctx, target, "PUT", `/internal/commercial/quotas/${market}/${plan}/${channel}`, change, "commercial.quota_set");
  await audit(ctx.actor, "commercial.quota_set", "SUCCESS", target, { market, plan, channel, monthlyLimit: change.monthlyLimit, remove: !!change.remove });
  return r;
}

export interface MerchantFilter { q?: string; market?: string; plan?: string; status?: string; page?: number; size?: number }

export function merchants(ctx: AuthContext, target: Target, f: MerchantFilter) {
  const p = new URLSearchParams();
  if (f.q) p.set("q", f.q.slice(0, 100));
  if (f.market) { check(MARKETS, f.market, "Mercado"); p.set("market", f.market); }
  if (f.plan) { check(PLANS, f.plan, "Plano"); p.set("plan", f.plan); }
  if (f.status) { if (!/^[A-Z_]{2,30}$/.test(f.status)) throw new HttpError(400, "BAD_REQUEST", "Situação inválida"); p.set("status", f.status); }
  p.set("page", String(Math.max(0, f.page ?? 0)));
  p.set("size", String(Math.min(200, Math.max(1, f.size ?? 50))));
  return adminCall<unknown>(ctx, target, "GET", `/internal/merchants?${p}`);
}

export function merchant(ctx: AuthContext, target: Target, id: string) {
  if (!/^[0-9a-f-]{36}$/i.test(id)) throw new HttpError(400, "BAD_REQUEST", "Lojista inválido");
  return adminCall<unknown>(ctx, target, "GET", `/internal/merchants/${id}`);
}

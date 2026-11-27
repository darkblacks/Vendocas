/** Types and labels for the commercial screens (markets, prices, quotas, merchants). Shapes mirror the API's /internal endpoints. */
export type Target = "TEST" | "LIVE";
export type MarketCode = "BR" | "LATAM" | "US" | "CA" | "EU";
export type Channel = "EMAIL" | "WHATSAPP" | "SMS";

export interface Market {
  code: MarketCode; name: string; currency: string; defaultLocale: string; locales: string[]; channelOrder: Channel[];
  sendWindowStart: string; sendWindowEnd: string; enabled: boolean; version: number;
}
export interface MarketWithCountries { market: Market; countries: { countryCode: string; defaultLocale: string; currency: string }[]; merchants: number }
export interface Price { id: string; currency: string; unitAmountMinor: number; stripePriceId?: string | null; marketCode?: string | null; offerText?: string | null; trialDays: number }
export interface Quota { channel: Channel; monthlyLimit: number | null; fairUseNote?: string | null }
export interface PlanPricing { planCode: string; planName: string; tier: number; features: string[]; price: Price | null; quotas: Quota[]; activeSubscriptions: number }
export interface MarketPricing { market: MarketCode; currency: string; enabled: boolean; plans: PlanPricing[] }
export interface CommercialView { target: Target; markets: MarketWithCountries[]; pricing: MarketPricing[] }

export interface MerchantRow {
  id: string; tradeName: string; contactEmail: string; countryCode: string; market: MarketCode; planCode?: string | null; subscriptionStatus?: string | null;
  priceMinor?: number | null; priceCurrency?: string | null; channels: Record<string, string>; messagesThisMonth: Record<string, number>;
  providerCostMicrosThisMonth: number; lastLoginAt?: string | null; createdAt: string; health: string[];
}
export interface MerchantPage { items: MerchantRow[]; total: number; page: number; size: number }
export interface QuotaUsage { channel: Channel; monthlyLimit: number | null; used: number; unlimited: boolean; payPerUse: boolean }
export interface MerchantDetail {
  merchant: MerchantRow; legalName: string; billingCountryCode: string; taxIdType?: string | null; taxIdStatus?: string | null; currentPeriodEnd?: string | null;
  channels: { channel: string; provider: string; status: string; statusReason?: string | null; actionRequired?: string | null; displayName?: string | null;
    lastVerifiedAt?: string | null; lastErrorAt?: string | null }[];
  quotas: QuotaUsage[]; blockedLast7Days: Record<string, number>; contacts: number; recoveredMinorThisMonth: number; recoveredSalesThisMonth: number;
}

export const MARKET_LABEL: Record<MarketCode, string> = { BR: "Brasil", LATAM: "LATAM", US: "Estados Unidos", CA: "Canadá", EU: "Europa" };
export const PLAN_LABEL: Record<string, string> = { ALERTA: "Alerta", ALERTA_VENDAS: "Alerta + Vendas", ILIMITADO: "Ilimitado" };
export const CHANNEL_LABEL: Record<string, string> = { EMAIL: "E-mail", WHATSAPP: "WhatsApp", SMS: "SMS", VOICE: "Voz" };
export const STATUS_LABEL: Record<string, string> = {
  ACTIVE: "Pagante", PAST_DUE: "Pagamento atrasado", GRACE_PERIOD: "Em carência", SUSPENDED: "Suspenso", CHECKOUT_PENDING: "No checkout",
  PAYMENT_PENDING: "Aguardando pagamento", DRAFT: "Rascunho", NONE: "Sem assinatura",
};
export const HEALTH_LABEL: Record<string, string> = {
  NO_SUBSCRIPTION: "Sem assinatura", PAYMENT_ISSUE: "Pagamento com problema", NO_WHATSAPP: "Sem WhatsApp", NO_LOGIN_7D: "Sem acesso há 7 dias",
  COST_ABOVE_REVENUE: "Custo acima da receita",
};
export const healthLabel = (h: string) => HEALTH_LABEL[h] ?? (h.startsWith("CHANNEL_") ? `Canal ${CHANNEL_LABEL[h.split("_")[1]!] ?? h.split("_")[1]} com problema` : h);
export const BLOCK_LABEL: Record<string, string> = {
  FREQUENCY_CAP: "Limite por cliente", NO_CONSENT: "Sem consentimento", CONSENT_MISSING: "Sem consentimento", PLAN_QUOTA_EXCEEDED: "Cota do plano esgotada",
  PLAN_CAMPAIGNS_REQUIRED: "Campanha fora do plano", PLAN_INBOX_REQUIRED: "Conversa fora do plano", CHANNEL_NOT_ACTIVE: "Canal desconectado",
  USAGE_RATE_NOT_CONFIGURED: "Tarifa não configurada", INSUFFICIENT_CREDITS: "Créditos insuficientes", SUBSCRIPTION_INACTIVE: "Assinatura inativa",
};

export function money(minor: number | null | undefined, currency: string | null | undefined): string {
  if (minor == null || !currency) return "—";
  return new Intl.NumberFormat("pt-BR", { style: "currency", currency }).format(minor / 100);
}

/** Provider costs are tracked in USD micros (1e-6 USD). */
export const usdFromMicros = (micros: number) => new Intl.NumberFormat("pt-BR", { style: "currency", currency: "USD" }).format(micros / 1_000_000);

/** "19,90" / "1.234,50" (pt-BR) or "19.90" → minor units. With a comma, dots are thousands separators; without, a dot is the decimal point. */
export function toMinor(v: string): number | null {
  const s = v.trim();
  const normalized = s.includes(",") ? s.replace(/\./g, "").replace(",", ".") : s;
  if (!/^\d+(\.\d{1,2})?$/.test(normalized)) return null;
  const n = Number(normalized);
  return Number.isFinite(n) && n > 0 ? Math.round(n * 100) : null;
}
export const fromMinor = (m: number | null | undefined) => (m == null ? "" : (m / 100).toFixed(2).replace(".", ","));

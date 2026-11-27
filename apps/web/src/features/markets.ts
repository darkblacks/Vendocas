/** Commercial markets, plans and CRM types shared by the public site and the merchant app (source of truth: the API). */
export const MARKETS = ["BR", "LATAM", "NA", "EU"] as const;
export type MarketCode = (typeof MARKETS)[number];
export const MARKET_CURRENCY: Record<MarketCode, string> = { BR: "BRL", LATAM: "USD", NA: "USD", EU: "EUR" };

export interface Quota { channel: "EMAIL" | "WHATSAPP" | "SMS"; monthlyLimit: number | null; fairUseNote?: string | null }

export interface MarketPlan {
  code: string; name: string; tier: number; features: string[]; market: MarketCode; currency: string;
  monthlyAmountMinor: number | null; offerText?: string | null; trialDays: number; quotas: Quota[]; purchasable: boolean;
}

export interface QuotaUsage {
  channel: "EMAIL" | "WHATSAPP" | "SMS"; monthlyLimit: number | null; used: number; unlimited: boolean; payPerUse: boolean;
  periodStart: string; periodEnd: string;
}

export interface UsageView { market: MarketCode; planCode?: string | null; channels: QuotaUsage[] }

export interface Entitlements { status?: string | null; planCode?: string | null; features: string[] }

/** Market a visitor most likely belongs to, from the page language (the signup country decides for real). */
export function marketForLocale(locale: string): MarketCode {
  switch (locale.slice(0, 2)) {
    case "pt": return "BR";
    case "es": return "LATAM";
    case "fr":
    case "de":
    case "it": return "EU";
    default: return "NA";
  }
}

export const STAGES = ["VISITOR", "NEW", "RECURRING", "VIP", "AT_RISK", "INACTIVE"] as const;
export type Stage = (typeof STAGES)[number];

/** Badge tone per customer stage (colour is never the only signal: the label is always shown). */
export const STAGE_TONE: Record<Stage, "neutral" | "info" | "success" | "warning" | "danger"> = {
  VISITOR: "neutral", NEW: "info", RECURRING: "success", VIP: "info", AT_RISK: "warning", INACTIVE: "neutral",
};

/** Features shown on plan cards, in display order (internal flags such as ALERTS_ONLY are explained separately). */
export const DISPLAY_FEATURES = ["ALERTS", "CRM", "SALES_REGISTRY", "CAMPAIGNS", "CAMPAIGNS_DAILY", "CUSTOM_SEGMENTS", "INBOX"] as const;

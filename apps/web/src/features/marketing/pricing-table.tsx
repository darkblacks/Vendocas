"use client";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useLocale, useTranslations } from "next-intl";
import { Check, Info, Minus } from "lucide-react";
import { api } from "@/lib/api";
import { money } from "@/lib/format";
import { Link } from "@/i18n/navigation";
import { Alert, Badge, Spinner, cn } from "@/components/ui";
import { DISPLAY_FEATURES, MARKETS, marketForLocale, type MarketCode, type MarketPlan } from "@/features/markets";

/**
 * Plans of a market with the market's price, offer, trial and included quotas. Prices come from the API (set by the
 * operator in the Control Center) — never hard-coded. Public mode lets the visitor switch market; app mode (onChoose)
 * shows the merchant's own market only.
 */
export function PricingTable({ onChoose, currentPlan }: { onChoose?: (code: string) => void; currentPlan?: string | null }) {
  const t = useTranslations("markets");
  const locale = useLocale();
  const inApp = !!onChoose;
  const [market, setMarket] = useState<MarketCode>(marketForLocale(locale));
  const q = useQuery({
    queryKey: inApp ? ["market-plans"] : ["public-plans", market],
    queryFn: () => inApp ? api<MarketPlan[]>("/billing/market-plans") : api<MarketPlan[]>(`/public/markets/${market}/plans`, { tenant: false }),
  });
  return (
    <div className="mt-6">
      {!inApp && (
        <div role="radiogroup" aria-label={t("choose")} className="mb-6 inline-flex flex-wrap gap-1 rounded-lg bg-muted p-1">
          {MARKETS.map((m) => (
            <button key={m} type="button" role="radio" aria-checked={market === m} onClick={() => setMarket(m)}
              className={cn("min-h-9 rounded-md px-3 text-sm", market === m ? "bg-card font-medium shadow-sm" : "text-muted-foreground hover:text-foreground")}>
              {t(`names.${m}`)}
            </button>
          ))}
        </div>
      )}
      {q.isLoading && <Spinner />}
      {(q.error || (q.data && q.data.length === 0)) && <Alert tone="warning" title={t("unavailable")} />}
      {q.data && q.data.length > 0 && (
        <div className="grid gap-4 md:grid-cols-3">
          {q.data.map((p) => <PlanCard key={p.code} plan={p} current={currentPlan === p.code} onChoose={onChoose} highlight={p.code === "ILIMITADO"} />)}
        </div>
      )}
    </div>
  );
}

function PlanCard({ plan: p, onChoose, current, highlight }: { plan: MarketPlan; onChoose?: (code: string) => void; current: boolean; highlight: boolean }) {
  const t = useTranslations("markets");
  const locale = useLocale();
  const alertsOnly = p.features.includes("ALERTS_ONLY");
  const priced = p.monthlyAmountMinor != null;
  return (
    <div className={cn("flex flex-col gap-4 rounded-xl border bg-card p-5", highlight ? "border-2 border-primary" : "border-border")}>
      <div className="flex items-start justify-between gap-2">
        <h3 className="text-lg font-semibold">{p.name}</h3>
        {current ? <Badge tone="success">{t("current")}</Badge> : highlight && <Badge tone="info">{t("mostComplete")}</Badge>}
      </div>
      <div>
        {priced
          ? <p className="text-3xl font-bold tabular-nums">{money(p.monthlyAmountMinor, p.currency, locale)}<span className="text-sm font-normal text-muted-foreground">{t("perMonth")}</span></p>
          : <p className="text-lg font-semibold text-muted-foreground">{t("comingSoon")}</p>}
        {p.offerText && <p className="mt-1 text-sm font-medium text-primary">{p.offerText}</p>}
        {p.trialDays > 0 && <p className="mt-1 text-sm text-muted-foreground">{t("trial", { days: p.trialDays })}</p>}
      </div>
      <ul className="space-y-2 text-sm">
        {DISPLAY_FEATURES.map((f) => {
          const on = p.features.includes(f);
          return (
            <li key={f} className={cn("flex gap-2", !on && "text-muted-foreground")}>
              {on ? <Check className="mt-0.5 h-4 w-4 shrink-0 text-success" aria-hidden /> : <Minus className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />}
              <span><span className="sr-only">{on ? t("included") : t("notIncluded")}: </span>{t(`features.${f}`)}</span>
            </li>
          );
        })}
      </ul>
      <div className="rounded-lg bg-muted p-3 text-sm">
        <p className="font-medium">{t("quotaTitle")}</p>
        <ul className="mt-1 space-y-0.5 text-muted-foreground">
          {(["WHATSAPP", "EMAIL", "SMS"] as const).map((ch) => {
            const q = p.quotas.find((x) => x.channel === ch);
            const value = !q ? t("payPerUse") : q.monthlyLimit == null ? t("unlimited") : t("perMonthCount", { n: q.monthlyLimit.toLocaleString(locale) });
            return <li key={ch} className="flex justify-between gap-2"><span>{t(`channels.${ch}`)}</span><span className="tabular-nums">{value}</span></li>;
          })}
        </ul>
      </div>
      {alertsOnly && <p className="flex gap-2 text-xs text-muted-foreground"><Info className="h-4 w-4 shrink-0" aria-hidden />{t("alertsOnlyNote")}</p>}
      <div className="mt-auto">
        {onChoose
          ? <button type="button" disabled={!p.purchasable || current} onClick={() => onChoose(p.code)}
              className="min-h-11 w-full rounded-lg bg-primary px-4 text-sm font-medium text-primary-foreground disabled:cursor-not-allowed disabled:opacity-50">
              {current ? t("current") : p.purchasable ? t("chooseCta") : t("comingSoon")}
            </button>
          : <Link href={`/register?plan=${p.code}&market=${p.market}`}
              className={cn("flex min-h-11 items-center justify-center rounded-lg px-4 text-sm font-medium",
                highlight ? "bg-primary text-primary-foreground" : "border border-border")}>
              {p.purchasable ? t("chooseCta") : t("createAccount")}
            </Link>}
      </div>
    </div>
  );
}

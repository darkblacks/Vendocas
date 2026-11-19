"use client";
import { useQuery } from "@tanstack/react-query";
import { useLocale, useTranslations } from "next-intl";
import { Check } from "lucide-react";
import { api } from "@/lib/api";
import { money } from "@/lib/format";
import { Link } from "@/i18n/navigation";
import { Alert, Badge, Spinner } from "@/components/ui";

export interface PlanView { code: string; name: string; tier: number; promotional: boolean; features: string[]; currency: string; monthlyAmountMinor: number; purchasable: boolean }

/** Prices come from the API (database is the source of truth) — never hard-coded in the UI. */
export function PricingTable({ onChoose }: { onChoose?: (code: string) => void }) {
  const t = useTranslations("landing");
  const locale = useLocale();
  const q = useQuery({ queryKey: ["plans"], queryFn: () => api<PlanView[]>("/billing/plans?currency=USD", { tenant: false }) });
  if (q.isLoading) return <div className="mt-8"><Spinner /></div>;
  if (q.error || !q.data) return <div className="mt-8"><Alert tone="warning" title="Pricing is temporarily unavailable" /></div>;
  return (
    <div className="mt-8 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
      {q.data.map((p) => (
        <div key={p.code} className={`flex flex-col rounded-lg border bg-card p-5 ${p.promotional ? "border-primary shadow-md" : "border-border"}`}>
          <div className="flex items-center justify-between">
            <h3 className="font-semibold">{p.name}</h3>
            {p.promotional && <Badge tone="info">{t("promo")}</Badge>}
          </div>
          <p className="mt-3 text-3xl font-bold">{money(p.monthlyAmountMinor, p.currency, locale)}<span className="text-sm font-normal text-muted-foreground">{t("perMonth")}</span></p>
          <ul className="mt-4 flex-1 space-y-2 text-sm">
            {p.features.filter((f) => f !== "REPORTS").map((f) => <li key={f} className="flex gap-2"><Check className="h-4 w-4 text-success" aria-hidden />{t.has(`features.${f}`) ? t(`features.${f}`) : f}</li>)}
          </ul>
          {onChoose
            ? <button onClick={() => onChoose(p.code)} className="mt-5 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground">{t("choose")}</button>
            : <Link href={`/register?plan=${p.code}`} className="mt-5 rounded-lg bg-primary px-4 py-2 text-center text-sm font-medium text-primary-foreground">{t("choose")}</Link>}
        </div>
      ))}
    </div>
  );
}

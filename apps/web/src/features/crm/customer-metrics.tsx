"use client";
import { useQuery } from "@tanstack/react-query";
import { useLocale, useTranslations } from "next-intl";
import { api } from "@/lib/api";
import { date, money } from "@/lib/format";
import { Badge, Card, CardTitle, Spinner } from "@/components/ui";
import { STAGE_TONE, type Stage } from "@/features/markets";

interface Metrics {
  stage: Stage; recency?: number | null; frequency?: number | null; monetary?: number | null; orders: number; totalSpentMinor: number;
  spent12mMinor: number; currency?: string | null; firstPurchaseAt?: string | null; lastPurchaseAt?: string | null; avgIntervalDays?: number | null;
  averageTicketMinor?: number | null; stageChangedAt?: string | null; favoriteProducts: string[];
}

/** Stage, RFM and purchase numbers of one customer (computed by the API after every purchase and daily). */
export function CustomerMetrics({ contactId }: { contactId: string }) {
  const t = useTranslations("crm");
  const ts = useTranslations("stages");
  const locale = useLocale();
  const q = useQuery({ queryKey: ["crm", "customer", contactId], queryFn: () => api<Metrics>(`/crm/customers/${contactId}`) });
  if (q.isLoading) return <Card><Spinner /></Card>;
  if (!q.data) return null;
  const m = q.data;
  const row = (k: string, v: React.ReactNode) => <div className="flex justify-between gap-4"><dt className="text-muted-foreground">{k}</dt><dd className="text-right tabular-nums">{v}</dd></div>;
  return (
    <Card>
      <div className="flex items-center justify-between gap-2">
        <CardTitle>{t("purchases")}</CardTitle>
        <Badge tone={STAGE_TONE[m.stage]}>{ts(m.stage)}</Badge>
      </div>
      <div className="mt-3 grid grid-cols-3 gap-2 rounded-lg bg-muted p-3 text-center">
        <div><p className="text-lg font-semibold tabular-nums">{money(m.totalSpentMinor, m.currency ?? undefined, locale)}</p><p className="text-xs text-muted-foreground">{t("totalSpent")}</p></div>
        <div><p className="text-lg font-semibold tabular-nums">{m.orders}</p><p className="text-xs text-muted-foreground">{t("orders")}</p></div>
        <div><p className="text-lg font-semibold tabular-nums">{money(m.averageTicketMinor, m.currency ?? undefined, locale)}</p><p className="text-xs text-muted-foreground">{t("avgTicket")}</p></div>
      </div>
      <dl className="mt-3 space-y-2 text-sm">
        {row(t("rfm"), m.recency ? <span title={t("rfmHint")}>{m.recency}-{m.frequency}-{m.monetary}</span> : "—")}
        {row(t("lastPurchase"), date(m.lastPurchaseAt, locale))}
        {row(t("firstPurchase"), date(m.firstPurchaseAt, locale))}
        {row(t("repurchaseEvery"), m.avgIntervalDays != null ? t("days", { n: Math.round(Number(m.avgIntervalDays)) }) : "—")}
        {row(t("spent12m"), money(m.spent12mMinor, m.currency ?? undefined, locale))}
        {row(t("favorites"), m.favoriteProducts.length ? m.favoriteProducts.join(", ") : "—")}
        {m.stageChangedAt && row(t("stageSince"), date(m.stageChangedAt, locale))}
      </dl>
    </Card>
  );
}

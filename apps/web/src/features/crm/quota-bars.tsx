"use client";
import { useQuery } from "@tanstack/react-query";
import { useLocale, useTranslations } from "next-intl";
import { api } from "@/lib/api";
import { Link } from "@/i18n/navigation";
import { Spinner, cn } from "@/components/ui";
import type { UsageView } from "@/features/markets";

/** Included messages of the current billing period, per channel. Amber from 80%, red when used up. */
export function QuotaBars({ className }: { className?: string }) {
  const t = useTranslations("home");
  const locale = useLocale();
  const q = useQuery({ queryKey: ["usage"], queryFn: () => api<UsageView>("/billing/usage") });
  if (q.isLoading) return <Spinner />;
  if (!q.data) return null;
  return (
    <div className={cn("space-y-4", className)}>
      {q.data.channels.map((c) => {
        const pct = c.monthlyLimit ? Math.min(100, Math.round((c.used / c.monthlyLimit) * 100)) : 0;
        const tone = pct >= 100 ? "bg-danger" : pct >= 80 ? "bg-warning" : "bg-primary";
        const label = c.payPerUse ? t("payPerUse") : c.unlimited ? t("unlimitedUsed", { used: c.used.toLocaleString(locale) })
          : t("of", { used: c.used.toLocaleString(locale), limit: c.monthlyLimit!.toLocaleString(locale) });
        return (
          <div key={c.channel}>
            <div className="mb-1.5 flex justify-between gap-2 text-sm">
              <span>{t(`channels.${c.channel}`)}</span>
              <span className={cn("tabular-nums", pct >= 80 && !c.unlimited && !c.payPerUse ? "font-semibold text-foreground" : "text-muted-foreground")}>{label}</span>
            </div>
            {!c.payPerUse && !c.unlimited && (
              <div className="h-2 overflow-hidden rounded-full bg-muted" role="progressbar" aria-label={t(`channels.${c.channel}`)}
                aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct}>
                <div className={cn("h-full rounded-full", tone)} style={{ width: `${pct}%` }} />
              </div>
            )}
          </div>
        );
      })}
      <Link href="/app/billing" className="inline-block text-sm text-primary">{t("buyExtra")}</Link>
    </div>
  );
}

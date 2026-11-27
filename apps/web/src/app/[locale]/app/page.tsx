"use client";
import { useQuery } from "@tanstack/react-query";
import { useLocale, useTranslations } from "next-intl";
import { api } from "@/lib/api";
import { money } from "@/lib/format";
import { useAuth } from "@/lib/auth";
import { Link } from "@/i18n/navigation";
import { Alert, Button, Card, CardTitle, PageHeader, Spinner, Stat, cn } from "@/components/ui";
import { OnboardingChecklist } from "@/features/onboarding/checklist";
import { QuotaBars } from "@/features/crm/quota-bars";
import { MARKET_CURRENCY, type Entitlements, type UsageView } from "@/features/markets";

interface Summary {
  currency?: string | null; recoveredMinor: number; recoveredSales: number; messagesSent: number; messagesEngaged: number;
  sentByChannel: Record<string, number>; atRisk: number; activeCustomers: number; newThisMonth: number; stages: Record<string, number>;
  byAlert: { automationId: string; name: string; revenueMinor: number; sales: number; sent: number }[];
}

/** Início: what the alerts brought in this month, quota usage, customers at risk and results per alert. */
export default function HomePage() {
  const t = useTranslations("home");
  const locale = useLocale();
  const { me, membership } = useAuth();
  const s = useQuery({ queryKey: ["crm-summary"], queryFn: () => api<Summary>("/crm/summary") });
  const ent = useQuery({ queryKey: ["entitlements"], queryFn: () => api<Entitlements>("/billing/entitlements") });
  const canSell = ent.data?.features.includes("SALES_REGISTRY");
  const usage = useQuery({ queryKey: ["usage"], queryFn: () => api<UsageView>("/billing/usage") });
  const d = s.data;
  const currency = d?.currency ?? (usage.data ? MARKET_CURRENCY[usage.data.market] : "USD");
  const firstName = me?.fullName?.split(" ")[0];
  const engagedPct = d && d.messagesSent > 0 ? Math.round((d.messagesEngaged / d.messagesSent) * 100) : null;
  return (
    <div className="space-y-6">
      <PageHeader title={firstName ? t("greeting", { name: firstName }) : t("title")}
        description={[membership?.organizationName, ent.data?.planCode ? (t.has(`plan.${ent.data.planCode}`) ? t(`plan.${ent.data.planCode}`) : ent.data.planCode) : null].filter(Boolean).join(" · ")}
        actions={canSell && <Button asChild><Link href="/app/sales">{t("registerSale")}</Link></Button>} />
      <OnboardingChecklist compact />
      {s.isLoading && <Spinner />}
      {s.error && <Alert tone="danger" title={String(s.error)} />}
      {d && (
        <>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <Card className="bg-primary text-primary-foreground">
              <p className="text-sm opacity-90">{t("recovered")}</p>
              <p className="mt-1 text-3xl font-bold tabular-nums">{money(d.recoveredMinor, currency, locale)}</p>
              <p className="text-sm opacity-90">{t("recoveredSales", { n: d.recoveredSales })}</p>
            </Card>
            <Stat label={t("sent")} value={d.messagesSent.toLocaleString(locale)} hint={engagedPct == null ? undefined : t("engaged", { pct: engagedPct })} />
            <Card>
              <p className="text-sm text-muted-foreground">{t("atRisk")}</p>
              <p className="mt-1 text-2xl font-semibold tabular-nums">{d.atRisk.toLocaleString(locale)}</p>
              <Link href="/app/contacts?stage=AT_RISK" className="text-sm text-primary">{t("viewList")}</Link>
            </Card>
            <Stat label={t("active")} value={d.activeCustomers.toLocaleString(locale)} hint={t("newThisMonth", { n: d.newThisMonth })} />
          </div>
          <div className="grid gap-4 lg:grid-cols-3">
            <Card>
              <CardTitle>{t("quota")}</CardTitle>
              <QuotaBars className="mt-4" />
            </Card>
            <Card className="lg:col-span-2">
              <CardTitle>{t("byAlert")}</CardTitle>
              {d.byAlert.length === 0
                ? <p className="mt-3 text-sm text-muted-foreground">{t("noAlerts")} <Link href="/app/automations" className="text-primary">{t("setupAlerts")}</Link></p>
                : (
                  <ul className="mt-3 divide-y divide-border text-sm">
                    {d.byAlert.map((a) => (
                      <li key={a.automationId} className="flex flex-wrap items-center justify-between gap-2 py-2.5">
                        <Link href={`/app/automations/${a.automationId}`} className="font-medium hover:text-primary">{a.name}</Link>
                        <span className="tabular-nums text-muted-foreground">
                          <b className={cn("text-foreground", a.revenueMinor === 0 && "font-normal")}>{money(a.revenueMinor, currency, locale)}</b>
                          {" · "}{t("sales", { n: a.sales })}{" · "}{t("sentCount", { n: a.sent })}
                        </span>
                      </li>
                    ))}
                  </ul>
                )}
            </Card>
          </div>
          <Card>
            <CardTitle>{t("stagesTitle")}</CardTitle>
            <div className="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-6">
              {Object.entries(d.stages).map(([stage, n]) => (
                <Link key={stage} href={`/app/contacts?stage=${stage}`} className="rounded-lg border border-border p-3 hover:bg-muted">
                  <p className="text-xs text-muted-foreground">{t(`stages.${stage}`)}</p>
                  <p className="text-xl font-semibold tabular-nums">{n.toLocaleString(locale)}</p>
                </Link>
              ))}
            </div>
          </Card>
        </>
      )}
    </div>
  );
}

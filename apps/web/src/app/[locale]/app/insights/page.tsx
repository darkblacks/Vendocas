"use client";
import { useQuery } from "@tanstack/react-query";
import { useLocale, useTranslations } from "next-intl";
import { api } from "@/lib/api";
import { date, money } from "@/lib/format";
import { Link } from "@/i18n/navigation";
import { Card, PageHeader, Spinner, cn } from "@/components/ui";
import { PlanBadge, useHasFeature } from "@/features/plan";

interface Row { id: string; name?: string | null; phone?: string | null; email?: string | null; orders: number; totalSpentMinor: number; currency?: string | null; lastPurchaseAt?: string | null }
interface Page { items: Row[]; total: number }
interface Reply { contactId: string; contactName?: string | null; stage: string; channel: string; body: string; receivedAt: string }
interface Weekday { weekday: string; purchases: number; amountMinor: number }
interface Persona { id: string; name: string; customers: number; averageTicketMinor?: number | null; currency?: string | null; topWeekday?: string | null }
interface Summary { currency?: string | null; stages: Record<string, number>; byAlert: { automationId: string; name: string; revenueMinor: number; sales: number }[] }

/** Entender meus clientes: one question per card, answered with what customers actually do (PDV, messages, replies). */
export default function InsightsPage() {
  const t = useTranslations("journey.insights");
  const locale = useLocale();
  const pipeline = useHasFeature("PIPELINE");
  const personasOn = useHasFeature("PERSONAS");
  const top = useQuery({ queryKey: ["crm", "top"], queryFn: () => api<Page>("/crm/customers?sort=totalSpent&dir=desc&size=5&minOrders=1") });
  const low = useQuery({ queryKey: ["crm", "low"], queryFn: () => api<Page>("/crm/customers?sort=ticket&dir=asc&size=5&minOrders=2") });
  const summary = useQuery({ queryKey: ["crm-summary"], queryFn: () => api<Summary>("/crm/summary") });
  const weekdays = useQuery({ queryKey: ["crm", "weekdays"], queryFn: () => api<Weekday[]>("/crm/insights/weekdays") });
  const replies = useQuery({ queryKey: ["crm", "replies"], enabled: !!pipeline, queryFn: () => api<Reply[]>("/crm/insights/replies?limit=8") });
  const personas = useQuery({ queryKey: ["personas"], enabled: !!personasOn, queryFn: () => api<Persona[]>("/crm/personas") });
  const max = Math.max(1, ...(weekdays.data ?? []).map((w) => w.purchases));
  const name = (r: { name?: string | null; phone?: string | null; email?: string | null }) => r.name || r.phone || r.email || "—";
  const ticket = (r: Row) => (r.orders ? Math.round(r.totalSpentMinor / r.orders) : 0);
  const gone = (summary.data?.stages.CHURN_RISK ?? 0) + (summary.data?.stages.CHURNED ?? 0);

  return (
    <div className="mx-auto max-w-6xl">
      <PageHeader title={t("title")} description={t("subtitle")} />
      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
        <Q title={t("why.title")} badge={pipeline === false ? "PIPELINE" : undefined}>
          {pipeline === false ? <p className="text-sm text-muted-foreground">{t("why.locked")}</p> : (<>
            <p className="text-sm text-muted-foreground">{t("why.text", { n: gone })}</p>
            {replies.data && replies.data.length === 0 && <p className="text-sm text-muted-foreground">{t("why.none")}</p>}
            <ul className="space-y-2">
              {replies.data?.map((r, i) => (
                <li key={i} className="rounded-lg bg-muted p-2.5 text-sm">
                  <p>“{r.body}”</p>
                  <p className="mt-1 text-xs text-muted-foreground"><Link href={`/app/contacts/${r.contactId}`} className="text-primary">{r.contactName ?? "—"}</Link> · {date(r.receivedAt, locale)}</p>
                </li>
              ))}
            </ul>
            <Link href="/app/contacts?stage=CHURN_RISK" className="text-sm text-primary">{t("why.list")}</Link>
          </>)}
        </Q>
        <Q title={t("top.title")}>
          {top.isLoading ? <Spinner /> : top.data?.items.length ? (
            <ol className="divide-y divide-border text-sm">
              {top.data.items.map((r, i) => (
                <li key={r.id} className="flex justify-between gap-2 py-2">
                  <Link href={`/app/contacts/${r.id}`} className="hover:text-primary">{i + 1}. {name(r)}</Link>
                  <span className="tabular-nums text-muted-foreground">{t("purchases", { n: r.orders })} · {money(r.totalSpentMinor, r.currency, locale)}</span>
                </li>
              ))}
            </ol>
          ) : <Empty />}
        </Q>
        <Q title={t("low.title")}>
          <p className="text-sm text-muted-foreground">{t("low.text")}</p>
          {low.isLoading ? <Spinner /> : low.data?.items.length ? (
            <ul className="divide-y divide-border text-sm">
              {low.data.items.map((r) => (
                <li key={r.id} className="flex justify-between gap-2 py-2">
                  <Link href={`/app/contacts/${r.id}`} className="hover:text-primary">{name(r)}</Link>
                  <span className="tabular-nums text-muted-foreground">{t("purchases", { n: r.orders })} · {t("ticket", { v: money(ticket(r), r.currency, locale) })}</span>
                </li>
              ))}
            </ul>
          ) : <Empty />}
        </Q>
        <Q title={t("buy.title")}>
          <p className="text-sm text-muted-foreground">{t("buy.text")}</p>
          {summary.data?.byAlert.length ? (
            <ul className="divide-y divide-border text-sm">
              {summary.data.byAlert.map((a) => (
                <li key={a.automationId} className="flex justify-between gap-2 py-2"><span>{a.name}</span>
                  <span className="tabular-nums text-muted-foreground">{t("sales", { n: a.sales })} · {money(a.revenueMinor, summary.data!.currency ?? "BRL", locale)}</span></li>
              ))}
            </ul>
          ) : <p className="text-sm text-muted-foreground">{t("buy.none")}</p>}
        </Q>
        <Q title={t("when.title")}>
          {weekdays.isLoading ? <Spinner /> : (
            <div className="flex h-36 items-end gap-2" role="img" aria-label={t("when.aria")}>
              {weekdays.data?.map((w) => (
                <div key={w.weekday} className="flex flex-1 flex-col items-center gap-1">
                  <span className="text-xs tabular-nums text-muted-foreground">{w.purchases}</span>
                  <div className="w-full rounded-t-md bg-[var(--brand-blue-light)]" style={{ height: `${Math.max(4, (w.purchases / max) * 100)}px` }} />
                  <span className="text-xs text-muted-foreground">{t(`days.${w.weekday}`)}</span>
                </div>
              ))}
            </div>
          )}
          <p className="text-xs text-muted-foreground">{t("when.hint")}</p>
        </Q>
        <Q title={t("personas.title")} badge={personasOn === false ? "PERSONAS" : undefined}>
          {personasOn === false ? <p className="text-sm text-muted-foreground">{t("personas.locked")}</p>
            : personas.data?.length ? (
              <ul className="divide-y divide-border text-sm">
                {personas.data.map((p) => (
                  <li key={p.id} className="flex justify-between gap-2 py-2"><span>{p.name} <span className="text-xs text-muted-foreground">({p.customers})</span></span>
                    <span className="tabular-nums text-muted-foreground">{p.averageTicketMinor != null ? money(p.averageTicketMinor, p.currency ?? "BRL", locale) : "—"}</span></li>
                ))}
              </ul>
            ) : <p className="text-sm text-muted-foreground">{t("personas.none")} <Link href="/app/personas" className="text-primary">{t("personas.create")}</Link></p>}
        </Q>
      </div>
    </div>
  );
}

function Q({ title, badge, children }: { title: string; badge?: string; children: React.ReactNode }) {
  return (
    <Card className={cn("flex flex-col gap-3", badge && "border-dashed")}>
      {badge && <PlanBadge feature={badge} className="self-start" />}
      <h2 className="font-semibold">{title}</h2>
      {children}
    </Card>
  );
}

function Empty() {
  const t = useTranslations("journey.insights");
  return <p className="text-sm text-muted-foreground">{t("empty")} <Link href="/app/sales" className="text-primary">{t("registerSale")}</Link></p>;
}

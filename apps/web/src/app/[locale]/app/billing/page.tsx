"use client";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useLocale, useTranslations } from "next-intl";
import { api, errorMessage } from "@/lib/api";
import { date, dateTime, money } from "@/lib/format";
import { useCan } from "@/lib/auth";
import { PricingTable } from "@/features/marketing/pricing-table";
import { QuotaBars } from "@/features/crm/quota-bars";
import { Alert, Button, Card, CardTitle, PageHeader, Spinner, Stat, StatusBadge, Table, Td } from "@/components/ui";

interface Overview {
  subscription?: { planCode: string; planName: string; status: string; collectionMethod: string; currency: string; amountMinor: number; currentPeriodEnd?: string;
    cancelAtPeriodEnd: boolean; pendingPlanCode?: string; graceUntil?: string };
  wallet: { currency: string; balanceMinor: number; reservedMinor: number }; providerConfigured: boolean; creditPacks: number[];
}
interface Invoice { id: string; kind: string; status: string; number?: string; currency: string; totalMinor: number; taxMinor: number; reverseCharge: boolean; dueAt?: string; paidAt?: string; hostedUrl?: string; pdfUrl?: string; fiscalStatus?: string; fiscalNumber?: string; createdAt: string }

export default function BillingPage() {
  const t = useTranslations("billing");
  const locale = useLocale();
  const canManage = useCan("billing");
  const o = useQuery({ queryKey: ["billing"], queryFn: () => api<Overview>("/billing/overview") });
  const invoices = useQuery({ queryKey: ["invoices"], queryFn: () => api<Invoice[]>("/billing/invoices") });
  const tx = useQuery({ queryKey: ["wallet-tx"], queryFn: () => api<{ type: string; amountMinor: number; balanceAfter: number; description?: string; createdAt: string }[]>("/billing/wallet/transactions") });
  const history = useQuery({ queryKey: ["sub-history"], queryFn: () => api<{ fromStatus?: string; toStatus: string; reason: string; actor: string; createdAt: string }[]>("/billing/subscription/history") });
  const [method, setMethod] = useState<"CARD" | "INVOICE">("CARD");
  const [error, setError] = useState<string | null>(null);
  const act = async (fn: () => Promise<unknown>) => { setError(null); try { await fn(); await o.refetch(); await history.refetch(); } catch (e) { setError(errorMessage(e)); } };
  if (o.isLoading) return <Spinner />;
  const s = o.data?.subscription;
  const w = o.data?.wallet;
  const live = s && ["ACTIVE", "PAST_DUE", "GRACE_PERIOD", "SUSPENDED", "PAYMENT_PENDING"].includes(s.status);
  return (
    <div className="space-y-6">
      <PageHeader title={t("title")} />
      {o.data && !o.data.providerConfigured && <Alert tone="warning" title={t("providerMissing")} />}
      {error && <Alert tone="danger" title={error} />}
      <div className="grid gap-4 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardTitle>{t("plan")}</CardTitle>
          {live ? (
            <div className="mt-3 space-y-2 text-sm">
              <p className="text-xl font-semibold">{s.planName} · {money(s.amountMinor, s.currency, locale)}/mo <StatusBadge status={s.status} /></p>
              <p className="text-muted-foreground">{s.collectionMethod === "SEND_INVOICE" ? t("invoice") : t("card")} · {t("nextCharge")}: {date(s.currentPeriodEnd, locale)}</p>
              {s.pendingPlanCode && <Alert title={t("pendingDowngrade", { plan: s.pendingPlanCode })} />}
              {s.graceUntil && <Alert tone="warning" title={`Grace until ${dateTime(s.graceUntil, locale)}`} />}
              {canManage && (
                <div className="flex flex-wrap gap-2 pt-2">
                  {s.collectionMethod === "CHARGE_AUTOMATICALLY" && <Button variant="outline" onClick={() => act(async () => { const r = await api<{ url: string }>("/billing/portal", { method: "POST" }); window.location.href = r.url; })}>{t("manageCard")}</Button>}
                  {s.cancelAtPeriodEnd
                    ? <Button onClick={() => act(() => api("/billing/subscription/resume", { method: "POST" }))}>{t("resume")}</Button>
                    : <Button variant="danger" onClick={() => confirm(t("cancelNote")) && act(() => api("/billing/subscription/cancel", { method: "POST" }))}>{t("cancel")}</Button>}
                </div>
              )}
              {s.cancelAtPeriodEnd && <Alert tone="warning" title={t("cancelNote")} />}
            </div>
          ) : <p className="mt-3 text-sm text-muted-foreground">{t("noSubscription")}</p>}
        </Card>
        <Card>
          <CardTitle>{t("wallet")}</CardTitle>
          {w && (<div className="mt-3 space-y-1 text-sm">
            <p className="text-2xl font-semibold">{money(w.balanceMinor - w.reservedMinor, w.currency, locale)}</p>
            <p className="text-muted-foreground">{t("reserved")}: {money(w.reservedMinor, w.currency, locale)}</p>
            {canManage && live && <div className="flex flex-wrap gap-1 pt-2">{o.data!.creditPacks.map((p) => (
              <Button key={p} size="sm" variant="outline" onClick={() => act(async () => { const r = await api<{ url: string }>("/billing/wallet/purchase", { body: { amountMinor: p } }); window.location.href = r.url; })}>+{money(p, w.currency, locale)}</Button>))}</div>}
          </div>)}
        </Card>
      </div>

      {canManage && (
        <Card>
          <CardTitle>{live ? t("changePlan") : t("subscribe")}</CardTitle>
          {!live && (
            <div className="mt-3 flex flex-wrap gap-4 text-sm">
              <label className="flex items-center gap-2"><input type="radio" checked={method === "CARD"} onChange={() => setMethod("CARD")} />{t("card")}</label>
              <label className="flex items-center gap-2"><input type="radio" checked={method === "INVOICE"} onChange={() => setMethod("INVOICE")} />{t("invoice")}</label>
            </div>
          )}
          {!live && method === "INVOICE" && <p className="mt-2 text-xs text-muted-foreground">{t("invoiceHelp")}</p>}
          {live && <p className="mt-2 text-xs text-muted-foreground">{t("upgradeNote")}</p>}
          <PricingTable currentPlan={live ? s.planCode : null} onChoose={(code) => act(async () => {
            if (live) await api("/billing/subscription/change-plan", { body: { planCode: code } });
            else { const r = await api<{ checkoutUrl?: string }>("/billing/subscription", { body: { planCode: code, method } }); if (r.checkoutUrl) window.location.href = r.checkoutUrl; }
          })} />
        </Card>
      )}

      {live && (
        <Card>
          <CardTitle>{t("usage")}</CardTitle>
          <p className="mt-1 text-sm text-muted-foreground">{t("usageHelp")}</p>
          <QuotaBars className="mt-4 max-w-xl" />
        </Card>
      )}

      <Card>
        <CardTitle>{t("invoices")}</CardTitle>
        <div className="mt-3">
          <Table head={["#", "Status", "Total", "Tax", "Due", "Paid", t("fiscal"), ""]}>
            {invoices.data?.map((i) => (
              <tr key={i.id}><Td>{i.number ?? "—"}<span className="block text-xs text-muted-foreground">{i.kind}</span></Td><Td><StatusBadge status={i.status} /></Td>
                <Td>{money(i.totalMinor, i.currency, locale)}</Td><Td>{money(i.taxMinor, i.currency, locale)}{i.reverseCharge && <span className="block text-xs">{t("reverseCharge")}</span>}</Td>
                <Td className="text-xs">{date(i.dueAt, locale)}</Td><Td className="text-xs">{date(i.paidAt, locale)}</Td>
                <Td>{i.fiscalStatus ? <><StatusBadge status={i.fiscalStatus} /> {i.fiscalNumber}</> : "—"}</Td>
                <Td className="space-x-2 text-xs">{i.hostedUrl && <a className="text-primary" href={i.hostedUrl} target="_blank" rel="noreferrer">pay/view</a>}{i.pdfUrl && <a className="text-primary" href={i.pdfUrl} target="_blank" rel="noreferrer">PDF</a>}</Td></tr>
            ))}
          </Table>
        </div>
      </Card>
      <div className="grid gap-4 lg:grid-cols-2">
        <Card><CardTitle>{t("transactions")}</CardTitle>
          <ul className="mt-3 space-y-1 text-xs">{tx.data?.map((x, i) => <li key={i}>{dateTime(x.createdAt, locale)} · {x.type} · {x.amountMinor} · {x.description}</li>)}</ul></Card>
        <Card><CardTitle>{t("history")}</CardTitle>
          <ul className="mt-3 space-y-1 text-xs">{history.data?.map((x, i) => <li key={i}>{dateTime(x.createdAt, locale)} · {x.fromStatus ?? "∅"} → {x.toStatus} · {x.reason} · {x.actor}</li>)}</ul></Card>
      </div>
      <Stat label="Note" value={<span className="text-sm font-normal">Billing documents from the payment provider are not fiscal documents. Brazilian NFS-e are issued separately when configured.</span>} />
    </div>
  );
}

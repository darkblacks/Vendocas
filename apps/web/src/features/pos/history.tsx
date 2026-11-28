"use client";
import { useEffect, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useLocale, useTranslations } from "next-intl";
import { api, errorMessage } from "@/lib/api";
import { money } from "@/lib/format";
import { useCan } from "@/lib/auth";
import { Link } from "@/i18n/navigation";
import { Alert, Badge, Button, Card, CardTitle, Dialog, DialogContent, Field, Input, Select, Spinner, Textarea } from "@/components/ui";
import type { ProductSales, SaleDetail, SaleRow } from "./types";

/** yyyy-mm-dd of "now" in the store's time zone (the API counts days in that zone, not the browser's). */
const todayIn = (timeZone?: string) => {
  try { return new Date().toLocaleDateString("en-CA", timeZone ? { timeZone } : undefined); } catch { return new Date().toLocaleDateString("en-CA"); }
};

/** Sales of a period with filters; a row opens the sale, where a manager can cancel it. Catalogue plans also see sales per product. */
export function HistoryTab({ catalog }: { catalog: boolean }) {
  const t = useTranslations("sales");
  const locale = useLocale();
  const org = useQuery({ queryKey: ["org", "current"], queryFn: () => api<{ timeZone?: string }>("/organizations/current") });
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  useEffect(() => {
    if (!org.data && !org.isError) return;
    const d = todayIn(org.data?.timeZone);
    setFrom((f) => f || d); setTo((x) => x || d);
  }, [org.data, org.isError]);
  const [status, setStatus] = useState("");
  const [q, setQ] = useState("");
  const [open, setOpen] = useState<string | null>(null);
  const params = new URLSearchParams({ from, to, ...(status ? { status } : {}), ...(q.trim().length >= 2 ? { q: q.trim() } : {}) }).toString();
  const sales = useQuery({ queryKey: ["sales", "history", params], enabled: !!from && !!to, queryFn: () => api<SaleRow[]>(`/sales?${params}`) });
  const byProduct = useQuery({ queryKey: ["sales", "by-product", from, to], enabled: catalog && !!from && !!to, queryFn: () => api<ProductSales[]>(`/sales/by-product?from=${from}&to=${to}`) });
  const completed = sales.data?.filter((s) => s.status === "COMPLETED") ?? [];
  const currency = sales.data?.[0]?.currency;
  const dt = new Intl.DateTimeFormat(locale, { dateStyle: "short", timeStyle: "short" });

  return (
    <div className="space-y-4">
      <Card className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Field label={t("history.from")} htmlFor="h-from"><Input id="h-from" type="date" value={from} max={to} onChange={(e) => setFrom(e.target.value)} /></Field>
        <Field label={t("history.to")} htmlFor="h-to"><Input id="h-to" type="date" value={to} min={from} onChange={(e) => setTo(e.target.value)} /></Field>
        <Field label={t("history.status")} htmlFor="h-status">
          <Select id="h-status" value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="">{t("history.all")}</option><option value="COMPLETED">{t("history.COMPLETED")}</option><option value="CANCELED">{t("history.CANCELED")}</option>
          </Select>
        </Field>
        <Field label={t("customer")} htmlFor="h-q"><Input id="h-q" value={q} placeholder={t("customerSearch")} onChange={(e) => setQ(e.target.value)} /></Field>
      </Card>

      <Card className="p-0">
        <div className="flex flex-wrap items-baseline justify-between gap-2 p-5 pb-3">
          <CardTitle>{t("history.title")}</CardTitle>
          {sales.data && <span className="text-sm text-muted-foreground">{t("history.totals", { n: completed.length, total: money(completed.reduce((n, s) => n + s.totalMinor, 0), currency ?? "BRL", locale) })}</span>}
        </div>
        {sales.isLoading && <div className="p-5"><Spinner /></div>}
        {sales.data?.length === 0 && <p className="px-5 pb-5 text-sm text-muted-foreground">{t("history.none")}</p>}
        {!!sales.data?.length && (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[640px] text-sm">
              <thead className="text-left text-xs text-muted-foreground"><tr>
                {[t("time"), t("customer"), t("items"), t("channel"), t("payment"), t("total")].map((h, i) => <th key={h} scope="col" className={`px-5 py-2 font-medium ${i === 5 ? "text-right" : ""}`}>{h}</th>)}
              </tr></thead>
              <tbody className="divide-y divide-border">
                {sales.data.map((x) => (
                  <tr key={x.id} className="cursor-pointer hover:bg-muted/60" onClick={() => setOpen(x.id)}>
                    <td className="whitespace-nowrap px-5 py-2.5 tabular-nums">
                      <button type="button" className="text-left hover:text-primary" onClick={(e) => { e.stopPropagation(); setOpen(x.id); }}>{dt.format(new Date(x.occurredAt))}</button>
                    </td>
                    <td className="px-5 py-2.5">{x.contactName || <span className="text-muted-foreground">{t("noCustomer")}</span>}</td>
                    <td className="px-5 py-2.5">{x.items > 1 ? t("itemsCount", { n: x.items }) : x.firstItem ?? "—"}</td>
                    <td className="px-5 py-2.5">{t(`channels.${x.salesChannel}`)}</td>
                    <td className="px-5 py-2.5">{t(`payments.${x.paymentMethod}`)}</td>
                    <td className="whitespace-nowrap px-5 py-2.5 text-right tabular-nums">
                      {x.status === "CANCELED" ? <><Badge tone="danger" className="mr-2">{t("history.CANCELED")}</Badge><s className="text-muted-foreground">{money(x.totalMinor, x.currency, locale)}</s></>
                        : money(x.totalMinor, x.currency, locale)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      {catalog && (
        <Card className="p-0">
          <div className="p-5 pb-3"><CardTitle>{t("history.byProduct")}</CardTitle></div>
          {byProduct.data?.length === 0 && <p className="px-5 pb-5 text-sm text-muted-foreground">{t("history.none")}</p>}
          {!!byProduct.data?.length && (
            <div className="overflow-x-auto">
              <table className="w-full min-w-[520px] text-sm">
                <thead className="text-left text-xs text-muted-foreground"><tr>
                  {[t("history.product"), t("category"), t("history.qty"), t("history.revenue"), t("history.margin")].map((h, i) => <th key={h} scope="col" className={`px-5 py-2 font-medium ${i >= 2 ? "text-right" : ""}`}>{h}</th>)}
                </tr></thead>
                <tbody className="divide-y divide-border">
                  {byProduct.data.map((p) => (
                    <tr key={(p.productId ?? "") + p.name}>
                      <td className="px-5 py-2.5">{p.name}</td>
                      <td className="px-5 py-2.5 text-muted-foreground">{p.category ?? "—"}</td>
                      <td className="px-5 py-2.5 text-right tabular-nums">{p.quantity}</td>
                      <td className="px-5 py-2.5 text-right tabular-nums">{money(p.revenueMinor, currency ?? "BRL", locale)}</td>
                      <td className="px-5 py-2.5 text-right tabular-nums">{p.marginMinor == null ? <span className="text-xs text-muted-foreground">{t("history.noCost")}</span>
                        : <>{money(p.marginMinor, currency ?? "BRL", locale)} <span className="text-xs text-muted-foreground">({Math.round((p.marginMinor / Math.max(p.revenueMinor, 1)) * 100)}%)</span></>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      )}
      {open && <SaleDialog id={open} onClose={() => setOpen(null)} />}
    </div>
  );
}

function SaleDialog({ id, onClose }: { id: string; onClose: () => void }) {
  const t = useTranslations("sales");
  const locale = useLocale();
  const qc = useQueryClient();
  const canManage = useCan("manage");
  const sale = useQuery({ queryKey: ["sales", "detail", id], queryFn: () => api<SaleDetail>(`/sales/${id}`) });
  const [reason, setReason] = useState("");
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const s = sale.data;
  const dt = new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeStyle: "short" });

  async function cancel() {
    setBusy(true); setError(null);
    try {
      await api(`/sales/${id}/cancel`, { body: { reason: reason.trim() || null } });
      await Promise.all(["sales", "crm", "products", "stock"].map((k) => qc.invalidateQueries({ queryKey: [k] })));
      setConfirming(false);
    } catch (e) { setError(errorMessage(e)); } finally { setBusy(false); }
  }

  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent title={s ? t("history.saleOf", { total: money(s.totalMinor, s.currency, locale) }) : t("history.title")}
        description={s ? dt.format(new Date(s.occurredAt)) : undefined}>
        {!s ? <Spinner /> : (
          <div className="space-y-4 text-sm">
            {s.status === "CANCELED" && <Alert tone="danger" title={t("history.canceledOn", { date: dt.format(new Date(s.canceledAt!)), name: s.canceledBy ?? "—" })}>{s.cancelReason}</Alert>}
            <dl className="grid grid-cols-2 gap-x-4 gap-y-2">
              <dt className="text-muted-foreground">{t("customer")}</dt>
              <dd>{s.contactId ? <Link href={`/app/contacts/${s.contactId}`} className="text-primary">{s.contactName || "—"}</Link> : t("noCustomer")}</dd>
              <dt className="text-muted-foreground">{t("payment")}</dt><dd>{t(`payments.${s.paymentMethod}`)}</dd>
              <dt className="text-muted-foreground">{t("channel")}</dt><dd>{t(`channels.${s.salesChannel}`)}</dd>
              <dt className="text-muted-foreground">{t("history.registeredBy")}</dt><dd>{s.registeredBy ?? "—"}</dd>
            </dl>
            {s.items.length > 0 && (
              <ul className="divide-y divide-border rounded-lg border border-border">
                {s.items.map((i, k) => (
                  <li key={k} className="flex justify-between gap-3 px-3 py-2">
                    <span>{i.quantity} × {i.description}</span>
                    <span className="tabular-nums">{money(i.quantity * i.unitAmountMinor, s.currency, locale)}</span>
                  </li>
                ))}
                {s.discountMinor > 0 && <li className="flex justify-between px-3 py-2 text-muted-foreground"><span>{t("pos.discount")}</span><span className="tabular-nums">−{money(s.discountMinor, s.currency, locale)}</span></li>}
              </ul>
            )}
            <div className="flex items-baseline justify-between border-t border-border pt-3">
              <span className="text-muted-foreground">{t("total")}</span><span className="text-xl font-bold tabular-nums">{money(s.totalMinor, s.currency, locale)}</span>
            </div>
            {s.status === "COMPLETED" && (canManage ? (
              confirming ? (
                <div className="space-y-2 rounded-lg border border-[var(--danger)]/40 p-3">
                  <p>{t("history.cancelConfirm")}</p>
                  <Field label={t("history.reason")} htmlFor="c-reason"><Textarea id="c-reason" rows={2} maxLength={300} value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
                  {error && <Alert tone="danger" title={error} />}
                  <div className="flex gap-2">
                    <Button variant="danger" loading={busy} onClick={cancel}>{t("history.cancel")}</Button>
                    <Button variant="ghost" onClick={() => setConfirming(false)}>{t("history.keep")}</Button>
                  </div>
                </div>
              ) : <Button variant="outline" onClick={() => setConfirming(true)}>{t("history.cancel")}</Button>
            ) : <p className="text-xs text-muted-foreground">{t("history.managerOnly")}</p>)}
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}

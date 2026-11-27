"use client";
import { useMemo, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useLocale, useTranslations } from "next-intl";
import { Minus, Plus, Trash2 } from "lucide-react";
import { api, ApiError, errorMessage } from "@/lib/api";
import { money, toMinor } from "@/lib/format";
import { useCan } from "@/lib/auth";
import { Link } from "@/i18n/navigation";
import { Alert, Button, Card, CardTitle, Dialog, DialogContent, DialogTrigger, EmptyState, Field, Input, PageHeader, Select, Spinner, Stat } from "@/components/ui";
import type { Contact, Page } from "@/features/types";
import { MARKET_CURRENCY, type Entitlements, type UsageView } from "@/features/markets";
import { PlanBadge } from "@/features/plan";

interface Product { id: string; name: string; sku?: string | null; priceMinor: number; currency: string; category?: string | null; active: boolean }
interface Sale { id: string; occurredAt: string; contactId?: string | null; contactName?: string | null; totalMinor: number; currency: string; salesChannel: string;
  paymentMethod: string; items: number; firstItem?: string | null }
interface Summary { totalMinor: number; sales: number; averageTicketMinor?: number | null; newCustomers: number; currency?: string | null }
type Line = { key: number; productId?: string; description: string; quantity: number; unitMinor: number };

const PAYMENTS = ["PIX", "CARD", "CASH", "BOLETO", "TRANSFER", "OTHER"] as const;
const SALE_CHANNELS = ["STORE", "WHATSAPP", "INSTAGRAM", "ONLINE", "PHONE", "OTHER"] as const;

/**
 * Vendocas PDV: the store registers what a customer bought elsewhere (counter, WhatsApp, Instagram) — the Vendocas does not
 * sell. Every plan: "venda seca" (customer + amount). Alerta + Vendas: products and items, so the CRM knows what each one buys.
 */
export default function SalesPage() {
  const t = useTranslations("sales");
  const locale = useLocale();
  const ent = useQuery({ queryKey: ["entitlements"], queryFn: () => api<Entitlements>("/billing/entitlements") });
  const usage = useQuery({ queryKey: ["usage"], queryFn: () => api<UsageView>("/billing/usage") });
  const features = ent.data?.features ?? [];
  const legacy = !!ent.data && !features.includes("ALERTS");
  const allowed = legacy || features.includes("SALES_REGISTRY");
  const catalog = legacy || features.includes("PRODUCT_CATALOG");
  const currency = usage.data ? MARKET_CURRENCY[usage.data.market] : "USD";
  const summary = useQuery({ queryKey: ["sales", "summary"], enabled: !!allowed, queryFn: () => api<Summary>("/sales/summary") });
  const sales = useQuery({ queryKey: ["sales", "today"], enabled: !!allowed, queryFn: () => api<Sale[]>("/sales") });

  if (ent.isLoading) return <Spinner />;
  if (!allowed) {
    return (
      <div>
        <PageHeader title={t("title")} description={t("subtitle")} />
        <EmptyState title={t("upgrade")} action={<Button asChild><Link href="/app/billing">{t("seePlans")}</Link></Button>} />
      </div>
    );
  }
  const s = summary.data;
  return (
    <div className="space-y-6">
      <PageHeader title={t("title")} description={t("subtitle")} actions={catalog ? <ProductsDialog currency={currency} /> : <PlanBadge feature="PRODUCT_CATALOG" />} />
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Stat label={t("soldToday")} value={money(s?.totalMinor ?? 0, s?.currency ?? currency, locale)} />
        <Stat label={t("count")} value={s?.sales ?? 0} />
        <Stat label={t("avgTicket")} value={money(s?.averageTicketMinor, s?.currency ?? currency, locale)} />
        <Stat label={t("newCustomers")} value={s?.newCustomers ?? 0} />
      </div>
      <div className="grid items-start gap-4 lg:grid-cols-[minmax(0,26rem)_minmax(0,1fr)]">
        <SaleForm currency={currency} catalog={catalog} />
        <Card className="p-0">
          <div className="p-5 pb-3"><CardTitle>{t("today")}</CardTitle></div>
          {sales.isLoading && <div className="p-5"><Spinner /></div>}
          {sales.data && sales.data.length === 0 && <p className="px-5 pb-5 text-sm text-muted-foreground">{t("noSales")}</p>}
          {sales.data && sales.data.length > 0 && (
            <div className="overflow-x-auto">
              <table className="w-full min-w-[560px] text-sm">
                <thead className="text-left text-xs text-muted-foreground"><tr>
                  {[t("time"), t("customer"), t("items"), t("channel"), t("payment"), t("total")].map((h, i) => <th key={h} scope="col" className={`px-5 py-2 font-medium ${i === 5 ? "text-right" : ""}`}>{h}</th>)}
                </tr></thead>
                <tbody className="divide-y divide-border">
                  {sales.data.map((x) => (
                    <tr key={x.id}>
                      <td className="whitespace-nowrap px-5 py-2.5 tabular-nums">{new Intl.DateTimeFormat(locale, { timeStyle: "short" }).format(new Date(x.occurredAt))}</td>
                      <td className="px-5 py-2.5">{x.contactId ? <Link href={`/app/contacts/${x.contactId}`} className="hover:text-primary">{x.contactName || "—"}</Link> : <span className="text-muted-foreground">{t("noCustomer")}</span>}</td>
                      <td className="px-5 py-2.5">{x.items > 1 ? t("itemsCount", { n: x.items }) : x.firstItem}</td>
                      <td className="px-5 py-2.5">{t(`channels.${x.salesChannel}`)}</td>
                      <td className="px-5 py-2.5">{t(`payments.${x.paymentMethod}`)}</td>
                      <td className="px-5 py-2.5 text-right tabular-nums">{money(x.totalMinor, x.currency, locale)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      </div>
    </div>
  );
}

function SaleForm({ currency, catalog }: { currency: string; catalog: boolean }) {
  const t = useTranslations("sales");
  const tm = useTranslations("markets");
  const locale = useLocale();
  const qc = useQueryClient();
  const canWrite = useCan("operate");
  const [customerQuery, setCustomerQuery] = useState("");
  const [customer, setCustomer] = useState<Contact | null>(null);
  const [newCustomer, setNewCustomer] = useState({ name: "", phone: "", email: "" });
  const [lines, setLines] = useState<Line[]>([]);
  const [custom, setCustom] = useState({ description: "", price: "" });
  const [amount, setAmount] = useState("");
  const [payment, setPayment] = useState<(typeof PAYMENTS)[number]>("PIX");
  const [channel, setChannel] = useState<(typeof SALE_CHANNELS)[number]>("STORE");
  const [consents, setConsents] = useState<string[]>(["WHATSAPP"]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const products = useQuery({ queryKey: ["products"], enabled: catalog, queryFn: () => api<Product[]>("/products") });
  const matches = useQuery({
    queryKey: ["contacts", "pick", customerQuery], enabled: customerQuery.trim().length >= 2 && !customer,
    queryFn: () => api<Page<Contact>>(`/contacts?size=5&q=${encodeURIComponent(customerQuery.trim())}`),
  });
  const total = useMemo(() => lines.length ? lines.reduce((n, l) => n + l.quantity * l.unitMinor, 0) : toMinor(amount), [lines, amount]);
  const hasNewCustomer = !customer && (newCustomer.phone.trim() || newCustomer.email.trim());

  const addProduct = (id: string) => {
    const p = products.data?.find((x) => x.id === id);
    if (!p) return;
    setLines((ls) => {
      const existing = ls.find((l) => l.productId === id);
      return existing ? ls.map((l) => (l === existing ? { ...l, quantity: l.quantity + 1 } : l))
        : [...ls, { key: Date.now(), productId: p.id, description: p.name, quantity: 1, unitMinor: p.priceMinor }];
    });
  };
  const addCustom = () => {
    const unit = toMinor(custom.price);
    if (!custom.description.trim() || unit <= 0) return;
    setLines((ls) => [...ls, { key: Date.now(), description: custom.description.trim(), quantity: 1, unitMinor: unit }]);
    setCustom({ description: "", price: "" });
  };

  async function submit() {
    setBusy(true); setError(null); setDone(null);
    try {
      const r = await api<{ totalMinor: number; currency: string }>("/sales", { body: {
        contactId: customer?.id ?? null,
        customer: hasNewCustomer ? { name: newCustomer.name || null, phone: newCustomer.phone || null, email: newCustomer.email || null } : null,
        items: lines.map((l) => l.productId ? { productId: l.productId, quantity: l.quantity } : { description: l.description, quantity: l.quantity, unitAmountMinor: l.unitMinor }),
        amountMinor: lines.length ? null : toMinor(amount),
        currency, paymentMethod: payment, salesChannel: channel,
        alertConsents: customer || hasNewCustomer ? consents : [],
      } });
      setDone(t("registered", { total: money(r.totalMinor, r.currency, locale) }));
      setLines([]); setAmount(""); setCustomer(null); setCustomerQuery(""); setNewCustomer({ name: "", phone: "", email: "" });
      await qc.invalidateQueries({ queryKey: ["sales"] });
      await qc.invalidateQueries({ queryKey: ["crm"] });
    } catch (e) {
      setError(e instanceof ApiError && e.status === 402 ? t("upgrade") : errorMessage(e));
    } finally { setBusy(false); }
  }

  return (
    <Card>
      <CardTitle>{t("newSale")}</CardTitle>
      <div className="mt-4 space-y-4">
        <div>
          <Field label={t("customer")} htmlFor="sale-customer">
            {customer
              ? <div className="flex min-h-10 items-center justify-between gap-2 rounded-lg border border-border px-3 text-sm">
                  <span>{customer.name || customer.email || customer.phoneE164}</span>
                  <Button variant="ghost" size="sm" onClick={() => setCustomer(null)}>{t("change")}</Button>
                </div>
              : <Input id="sale-customer" placeholder={t("customerSearch")} value={customerQuery} onChange={(e) => setCustomerQuery(e.target.value)} autoComplete="off" />}
          </Field>
          {!customer && matches.data && matches.data.items.length > 0 && (
            <ul className="mt-1 divide-y divide-border rounded-lg border border-border text-sm">
              {matches.data.items.map((c) => (
                <li key={c.id}><button type="button" className="flex min-h-10 w-full items-center justify-between px-3 text-left hover:bg-muted" onClick={() => setCustomer(c)}>
                  <span>{c.name || "—"}</span><span className="text-xs text-muted-foreground">{c.phoneE164 ?? c.email}</span>
                </button></li>
              ))}
            </ul>
          )}
          {!customer && (
            <details className="mt-2 text-sm">
              <summary className="cursor-pointer text-primary">{t("newCustomer")}</summary>
              <div className="mt-2 grid gap-2 sm:grid-cols-2">
                <Field label={t("name")} htmlFor="nc-name"><Input id="nc-name" value={newCustomer.name} onChange={(e) => setNewCustomer({ ...newCustomer, name: e.target.value })} /></Field>
                <Field label={t("phone")} htmlFor="nc-phone"><Input id="nc-phone" type="tel" value={newCustomer.phone} onChange={(e) => setNewCustomer({ ...newCustomer, phone: e.target.value })} /></Field>
                <div className="sm:col-span-2"><Field label={t("email")} htmlFor="nc-email"><Input id="nc-email" type="email" value={newCustomer.email} onChange={(e) => setNewCustomer({ ...newCustomer, email: e.target.value })} /></Field></div>
              </div>
            </details>
          )}
        </div>

        {lines.length === 0 && (
          <Field label={t("amountPaid")} htmlFor="sale-amount" hint={catalog ? t("amountHint") : t("drySaleHint")}>
            <Input id="sale-amount" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="159,90" />
          </Field>
        )}
        {catalog ? (
          <div>
          <p className="mb-1.5 text-sm font-medium">{t("itemsOptional")}</p>
          {lines.length > 0 && (
            <ul className="mb-2 divide-y divide-border rounded-lg border border-border text-sm">
              {lines.map((l) => (
                <li key={l.key} className="flex items-center gap-2 px-3 py-2">
                  <span className="min-w-0 flex-1 truncate">{l.description}<span className="block text-xs text-muted-foreground tabular-nums">{money(l.unitMinor, currency, locale)}</span></span>
                  <Button variant="outline" size="icon" aria-label={t("less")} onClick={() => setLines((ls) => ls.map((x) => x.key === l.key ? { ...x, quantity: Math.max(1, x.quantity - 1) } : x))}><Minus className="h-4 w-4" /></Button>
                  <span className="w-6 text-center tabular-nums">{l.quantity}</span>
                  <Button variant="outline" size="icon" aria-label={t("more")} onClick={() => setLines((ls) => ls.map((x) => x.key === l.key ? { ...x, quantity: x.quantity + 1 } : x))}><Plus className="h-4 w-4" /></Button>
                  <Button variant="ghost" size="icon" aria-label={t("remove")} onClick={() => setLines((ls) => ls.filter((x) => x.key !== l.key))}><Trash2 className="h-4 w-4" /></Button>
                </li>
              ))}
            </ul>
          )}
          <Select aria-label={t("addProduct")} value="" onChange={(e) => addProduct(e.target.value)}>
            <option value="">{products.data?.length ? t("addProduct") : t("noProducts")}</option>
            {products.data?.map((p) => <option key={p.id} value={p.id}>{p.name} · {money(p.priceMinor, p.currency, locale)}</option>)}
          </Select>
          <div className="mt-2 flex gap-2">
            <Input aria-label={t("customItem")} placeholder={t("customItem")} value={custom.description} onChange={(e) => setCustom({ ...custom, description: e.target.value })} />
            <Input aria-label={t("unitPrice")} placeholder={t("unitPrice")} inputMode="decimal" className="w-28" value={custom.price} onChange={(e) => setCustom({ ...custom, price: e.target.value })} />
            <Button variant="outline" onClick={addCustom}>{t("add")}</Button>
          </div>
        </div>
        ) : (
          <p className="flex flex-wrap items-center gap-2 rounded-lg bg-muted p-3 text-xs text-muted-foreground"><PlanBadge feature="PRODUCT_CATALOG" />{t("productsLocked")}</p>
        )}

        <div className="grid gap-3 sm:grid-cols-2">
          <Field label={t("payment")} htmlFor="sale-pay"><Select id="sale-pay" value={payment} onChange={(e) => setPayment(e.target.value as typeof payment)}>{PAYMENTS.map((p) => <option key={p} value={p}>{t(`payments.${p}`)}</option>)}</Select></Field>
          <Field label={t("channel")} htmlFor="sale-ch"><Select id="sale-ch" value={channel} onChange={(e) => setChannel(e.target.value as typeof channel)}>{SALE_CHANNELS.map((c) => <option key={c} value={c}>{t(`channels.${c}`)}</option>)}</Select></Field>
        </div>

        {(customer || hasNewCustomer) && (
          <fieldset className="text-sm">
            <legend className="mb-1.5 font-medium">{t("consent")}</legend>
            <div className="flex flex-wrap gap-4">
              {["WHATSAPP", "EMAIL", "SMS"].map((ch) => (
                <label key={ch} className="flex min-h-9 items-center gap-2">
                  <input type="checkbox" className="h-4 w-4 accent-[var(--primary)]" checked={consents.includes(ch)}
                    onChange={(e) => setConsents((cs) => e.target.checked ? [...cs, ch] : cs.filter((x) => x !== ch))} />
                  {tm(`channels.${ch}`)}
                </label>
              ))}
            </div>
          </fieldset>
        )}

        <div className="flex items-baseline justify-between border-t border-border pt-3">
          <span className="text-muted-foreground">{t("total")}</span>
          <span className="text-2xl font-bold tabular-nums">{money(total, currency, locale)}</span>
        </div>
        {error && <Alert tone="danger" title={error} />}
        {done && <Alert tone="success" title={done}>{t("thankYouNote")}</Alert>}
        <Button className="w-full" size="lg" disabled={!canWrite || total <= 0 || (!customer && !hasNewCustomer)} loading={busy} onClick={submit}>{t("register")}</Button>
      </div>
    </Card>
  );
}

function ProductsDialog({ currency }: { currency: string }) {
  const t = useTranslations("sales");
  const locale = useLocale();
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  const [f, setF] = useState({ name: "", sku: "", price: "", category: "" });
  const [error, setError] = useState<string | null>(null);
  const products = useQuery({ queryKey: ["products", "all"], enabled: open, queryFn: () => api<Product[]>("/products?includeInactive=true") });
  async function save() {
    setError(null);
    try {
      await api("/products", { body: { name: f.name, sku: f.sku || null, priceMinor: toMinor(f.price), currency, category: f.category || null } });
      setF({ name: "", sku: "", price: "", category: "" });
      await qc.invalidateQueries({ queryKey: ["products"] });
    } catch (e) { setError(errorMessage(e)); }
  }
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild><Button variant="outline">{t("products")}</Button></DialogTrigger>
      <DialogContent title={t("products")} description={t("productsHelp")}>
        <div className="space-y-4">
          <div className="grid gap-2 sm:grid-cols-2">
            <Field label={t("name")} htmlFor="p-name"><Input id="p-name" value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} /></Field>
            <Field label={t("unitPrice")} htmlFor="p-price"><Input id="p-price" inputMode="decimal" value={f.price} onChange={(e) => setF({ ...f, price: e.target.value })} /></Field>
            <Field label="SKU" htmlFor="p-sku"><Input id="p-sku" value={f.sku} onChange={(e) => setF({ ...f, sku: e.target.value })} /></Field>
            <Field label={t("category")} htmlFor="p-cat"><Input id="p-cat" value={f.category} onChange={(e) => setF({ ...f, category: e.target.value })} /></Field>
          </div>
          {error && <Alert tone="danger" title={error} />}
          <Button onClick={save} disabled={!f.name || toMinor(f.price) <= 0}>{t("addProductCta")}</Button>
          <ul className="max-h-64 divide-y divide-border overflow-y-auto rounded-lg border border-border text-sm">
            {products.data?.map((p) => (
              <li key={p.id} className="flex justify-between gap-2 px-3 py-2"><span>{p.name}{p.sku && <span className="text-xs text-muted-foreground"> · {p.sku}</span>}</span>
                <span className="tabular-nums">{money(p.priceMinor, p.currency, locale)}</span></li>
            ))}
            {products.data?.length === 0 && <li className="px-3 py-2 text-muted-foreground">{t("noProducts")}</li>}
          </ul>
        </div>
      </DialogContent>
    </Dialog>
  );
}


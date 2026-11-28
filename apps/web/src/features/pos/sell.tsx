"use client";
import { useMemo, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useLocale, useTranslations } from "next-intl";
import { Minus, Plus, ScanBarcode, Trash2 } from "lucide-react";
import { api, ApiError, errorMessage } from "@/lib/api";
import { money } from "@/lib/format";
import { useCan } from "@/lib/auth";
import { Alert, Badge, Button, Card, CardTitle, Field, Input, Select, Spinner, cn } from "@/components/ui";
import type { Contact, Page } from "@/features/types";
import { PlanBadge } from "@/features/plan";
import { PAYMENTS, SALE_CHANNELS, parseMoney, type Category, type Product } from "./types";
import { ProductPhoto } from "./product-photo";

type Line = { key: string; productId?: string; description: string; quantity: number; unitMinor: number; stock?: number; track?: boolean };

/** Counter: product grid (search, category chips, barcode scanner) on the left, the cart on the right. */
export function SellTab({ currency, catalog }: { currency: string; catalog: boolean }) {
  const [lines, setLines] = useState<Line[]>([]);
  const add = (p: Product) => setLines((ls) => {
    const existing = ls.find((l) => l.productId === p.id);
    return existing ? ls.map((l) => (l === existing ? { ...l, quantity: l.quantity + 1 } : l))
      : [...ls, { key: p.id, productId: p.id, description: p.name, quantity: 1, unitMinor: p.priceMinor, stock: p.stockQuantity, track: p.trackStock }];
  });
  if (!catalog) return <div className="max-w-lg"><Cart currency={currency} catalog={false} lines={[]} setLines={() => {}} /></div>;
  return (
    <div className="grid items-start gap-4 xl:grid-cols-[minmax(0,1fr)_minmax(0,26rem)]">
      <ProductGrid currency={currency} onAdd={add} />
      <Cart currency={currency} catalog lines={lines} setLines={setLines} />
    </div>
  );
}

function ProductGrid({ currency, onAdd }: { currency: string; onAdd: (p: Product) => void }) {
  const t = useTranslations("sales");
  const locale = useLocale();
  const [q, setQ] = useState("");
  const [cat, setCat] = useState<string | null>(null);
  const [hint, setHint] = useState<string | null>(null);
  const products = useQuery({ queryKey: ["products"], queryFn: () => api<Product[]>("/products") });
  const categories = useQuery({ queryKey: ["product-categories"], queryFn: () => api<Category[]>("/product-categories") });
  const shown = useMemo(() => {
    const s = q.trim().toLowerCase();
    return (products.data ?? []).filter((p) => (!cat || p.categoryId === cat)
      && (!s || p.name.toLowerCase().includes(s) || p.sku?.toLowerCase().includes(s) || p.barcode === q.trim()));
  }, [products.data, q, cat]);

  /** A scanner types the barcode and presses Enter: add that product straight away. */
  function onEnter() {
    const code = q.trim();
    if (!code) return;
    const exact = products.data?.find((p) => p.barcode === code || p.sku?.toLowerCase() === code.toLowerCase());
    const only = shown.length === 1 ? shown[0] : undefined;
    const p = exact ?? only;
    if (p) { onAdd(p); setQ(""); setHint(null); } else setHint(t("pos.notFound", { code }));
  }

  return (
    <Card>
      <div className="relative">
        <ScanBarcode className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" aria-hidden />
        <Input aria-label={t("pos.search")} placeholder={t("pos.search")} className="pl-9" value={q} autoFocus
          onChange={(e) => { setQ(e.target.value); setHint(null); }} onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); onEnter(); } }} />
      </div>
      {hint && <p className="mt-2 text-sm text-[var(--warning)]" role="status">{hint}</p>}
      {(categories.data?.length ?? 0) > 0 && (
        <div className="mt-3 flex gap-2 overflow-x-auto pb-1" role="group" aria-label={t("pos.categories")}>
          {[{ id: null as string | null, name: t("pos.allCategories") }, ...(categories.data ?? [])].map((c) => (
            <button key={c.id ?? "all"} type="button" aria-pressed={cat === c.id} onClick={() => setCat(c.id)}
              className={cn("min-h-9 shrink-0 rounded-full border px-3 text-sm", cat === c.id ? "border-primary bg-primary text-primary-foreground" : "border-border hover:bg-muted")}>
              {c.name}
            </button>
          ))}
        </div>
      )}
      {products.isLoading && <div className="mt-4"><Spinner /></div>}
      {products.data && shown.length === 0 && <p className="mt-6 text-center text-sm text-muted-foreground">{products.data.length ? t("pos.noMatch") : t("noProducts")}</p>}
      <ul className="mt-4 grid grid-cols-2 gap-3 sm:grid-cols-3 2xl:grid-cols-4">
        {shown.map((p) => (
          <li key={p.id}>
            <button type="button" onClick={() => onAdd(p)} className="group flex h-full w-full flex-col overflow-hidden rounded-xl border border-border bg-card text-left hover:border-primary">
              <ProductPhoto url={p.photoUrl} name={p.name} className="aspect-[4/3] w-full rounded-none" />
              <span className="flex flex-1 flex-col gap-1 p-3">
                <span className="line-clamp-2 text-sm font-medium group-hover:text-primary">{p.name}</span>
                <span className="mt-auto flex items-center justify-between gap-2">
                  <span className="font-semibold tabular-nums">{money(p.priceMinor, p.currency ?? currency, locale)}</span>
                  {p.trackStock && <Badge tone={p.stockQuantity <= 0 ? "danger" : p.lowStock ? "warning" : "neutral"}>
                    {p.stockQuantity <= 0 ? t("pos.out") : t("pos.left", { n: p.stockQuantity })}</Badge>}
                </span>
              </span>
            </button>
          </li>
        ))}
      </ul>
    </Card>
  );
}

function Cart({ currency, catalog, lines, setLines }: { currency: string; catalog: boolean; lines: Line[]; setLines: React.Dispatch<React.SetStateAction<Line[]>> }) {
  const t = useTranslations("sales");
  const tm = useTranslations("markets");
  const locale = useLocale();
  const qc = useQueryClient();
  const canWrite = useCan("operate");
  const [customerQuery, setCustomerQuery] = useState("");
  const [customer, setCustomer] = useState<Contact | null>(null);
  const [newCustomer, setNewCustomer] = useState({ name: "", phone: "", email: "" });
  const [custom, setCustom] = useState({ description: "", price: "" });
  const [amount, setAmount] = useState("");
  const [discount, setDiscount] = useState("");
  const [payment, setPayment] = useState<(typeof PAYMENTS)[number]>("PIX");
  const [channel, setChannel] = useState<(typeof SALE_CHANNELS)[number]>("STORE");
  const [consents, setConsents] = useState<string[]>(["WHATSAPP"]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const seq = useRef(0);
  const matches = useQuery({
    queryKey: ["contacts", "pick", customerQuery], enabled: customerQuery.trim().length >= 2 && !customer,
    queryFn: () => api<Page<Contact>>(`/contacts?size=5&q=${encodeURIComponent(customerQuery.trim())}`),
  });
  const subtotal = lines.reduce((n, l) => n + l.quantity * l.unitMinor, 0);
  const discountMinor = lines.length ? parseMoney(discount) || 0 : 0;
  const badDiscount = Number.isNaN(parseMoney(discount)) || discountMinor > subtotal;
  const total = lines.length ? subtotal - discountMinor : parseMoney(amount) || 0;
  const hasNewCustomer = !customer && (newCustomer.phone.trim() || newCustomer.email.trim());

  const addCustom = () => {
    const unit = parseMoney(custom.price);
    if (!custom.description.trim() || !(unit > 0)) return;
    setLines((ls) => [...ls, { key: `c${++seq.current}`, description: custom.description.trim(), quantity: 1, unitMinor: unit }]);
    setCustom({ description: "", price: "" });
  };
  const setQty = (key: string, f: (q: number) => number) => setLines((ls) => ls.map((x) => (x.key === key ? { ...x, quantity: Math.max(1, f(x.quantity)) } : x)));

  async function submit() {
    setBusy(true); setError(null); setDone(null);
    try {
      const r = await api<{ totalMinor: number; currency: string }>("/sales", { body: {
        contactId: customer?.id ?? null,
        customer: hasNewCustomer ? { name: newCustomer.name || null, phone: newCustomer.phone || null, email: newCustomer.email || null } : null,
        items: lines.map((l) => (l.productId ? { productId: l.productId, quantity: l.quantity } : { description: l.description, quantity: l.quantity, unitAmountMinor: l.unitMinor })),
        amountMinor: lines.length ? null : total,
        discountMinor: discountMinor || null,
        currency, paymentMethod: payment, salesChannel: channel,
        alertConsents: customer || hasNewCustomer ? consents : [],
      } });
      setDone(t("registered", { total: money(r.totalMinor, r.currency, locale) }));
      setLines([]); setAmount(""); setDiscount(""); setCustomer(null); setCustomerQuery(""); setNewCustomer({ name: "", phone: "", email: "" });
      await Promise.all(["sales", "crm", "products", "stock"].map((k) => qc.invalidateQueries({ queryKey: [k] })));
    } catch (e) {
      setError(e instanceof ApiError && e.status === 402 ? t("upgrade") : errorMessage(e));
    } finally { setBusy(false); }
  }

  return (
    <Card className="xl:sticky xl:top-4">
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

        {catalog ? (
          <div>
            <p className="mb-1.5 text-sm font-medium">{t("pos.cart")}</p>
            {lines.length === 0 && <p className="rounded-lg border border-dashed border-border p-3 text-center text-sm text-muted-foreground">{t("pos.emptyCart")}</p>}
            {lines.length > 0 && (
              <ul className="divide-y divide-border rounded-lg border border-border text-sm">
                {lines.map((l) => (
                  <li key={l.key} className="flex items-center gap-2 px-3 py-2">
                    <span className="min-w-0 flex-1">
                      <span className="block truncate">{l.description}</span>
                      <span className="block text-xs tabular-nums text-muted-foreground">{money(l.unitMinor, currency, locale)}
                        {l.track && l.stock != null && l.quantity > l.stock && <span className="ml-1 text-[var(--warning)]">· {t("pos.beyondStock", { n: l.stock })}</span>}</span>
                    </span>
                    <Button variant="outline" size="icon" aria-label={t("less")} onClick={() => setQty(l.key, (q) => q - 1)}><Minus className="h-4 w-4" /></Button>
                    <span className="w-7 text-center tabular-nums" aria-live="polite">{l.quantity}</span>
                    <Button variant="outline" size="icon" aria-label={t("more")} onClick={() => setQty(l.key, (q) => q + 1)}><Plus className="h-4 w-4" /></Button>
                    <Button variant="ghost" size="icon" aria-label={t("remove")} onClick={() => setLines((ls) => ls.filter((x) => x.key !== l.key))}><Trash2 className="h-4 w-4" /></Button>
                  </li>
                ))}
              </ul>
            )}
            <details className="mt-2 text-sm">
              <summary className="cursor-pointer text-primary">{t("customItem")}</summary>
              <div className="mt-2 flex gap-2">
                <Input aria-label={t("customItem")} placeholder={t("customItem")} value={custom.description} onChange={(e) => setCustom({ ...custom, description: e.target.value })} />
                <Input aria-label={t("unitPrice")} placeholder={t("unitPrice")} inputMode="decimal" className="w-28" value={custom.price} onChange={(e) => setCustom({ ...custom, price: e.target.value })} />
                <Button variant="outline" onClick={addCustom}>{t("add")}</Button>
              </div>
            </details>
            {lines.length === 0 && (
              <div className="mt-3"><Field label={t("amountPaid")} htmlFor="sale-amount" hint={t("amountHint")}>
                <Input id="sale-amount" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="159,90" />
              </Field></div>
            )}
          </div>
        ) : (
          <>
            <Field label={t("amountPaid")} htmlFor="sale-amount" hint={t("drySaleHint")}>
              <Input id="sale-amount" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="159,90" />
            </Field>
            <p className="flex flex-wrap items-center gap-2 rounded-lg bg-muted p-3 text-xs text-muted-foreground"><PlanBadge feature="PRODUCT_CATALOG" />{t("productsLocked")}</p>
          </>
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
                    onChange={(e) => setConsents((cs) => (e.target.checked ? [...cs, ch] : cs.filter((x) => x !== ch)))} />
                  {tm(`channels.${ch}`)}
                </label>
              ))}
            </div>
          </fieldset>
        )}

        <div className="space-y-1 border-t border-border pt-3 text-sm">
          {lines.length > 0 && <>
            <div className="flex justify-between"><span className="text-muted-foreground">{t("pos.subtotal")}</span><span className="tabular-nums">{money(subtotal, currency, locale)}</span></div>
            <div className="flex items-center justify-between gap-3">
              <label htmlFor="sale-discount" className="text-muted-foreground">{t("pos.discount")}</label>
              <Input id="sale-discount" inputMode="decimal" className="h-9 w-28 text-right" value={discount} onChange={(e) => setDiscount(e.target.value)} placeholder="0,00"
                aria-invalid={badDiscount || undefined} />
            </div>
            {badDiscount && <p className="text-right text-xs text-[var(--danger)]">{t("pos.badDiscount")}</p>}
          </>}
          <div className="flex items-baseline justify-between pt-1">
            <span className="text-muted-foreground">{t("total")}</span>
            <span className="text-2xl font-bold tabular-nums">{money(total, currency, locale)}</span>
          </div>
        </div>
        {error && <Alert tone="danger" title={error} />}
        {done && <Alert tone="success" title={done}>{t("thankYouNote")}</Alert>}
        <Button className="w-full" size="lg" disabled={!canWrite || total <= 0 || badDiscount || (!customer && !hasNewCustomer)} loading={busy} onClick={submit}>{t("register")}</Button>
      </div>
    </Card>
  );
}

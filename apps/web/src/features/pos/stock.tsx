"use client";
import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useLocale, useTranslations } from "next-intl";
import { api, errorMessage } from "@/lib/api";
import { money } from "@/lib/format";
import { useCan } from "@/lib/auth";
import { Alert, Badge, Button, Card, CardTitle, Dialog, DialogContent, Field, Input, Select, Spinner, Stat } from "@/components/ui";
import { parseMoney, toInput, type Movement, type Product, type StockSummary } from "./types";
import { ProductPhoto } from "./product-photo";

type Kind = "PURCHASE" | "LOSS" | "COUNT";

/** Stock: value and alerts, what to restock, entries / losses / inventory counts and the latest movements. */
export function StockTab({ currency }: { currency: string }) {
  const t = useTranslations("sales");
  const locale = useLocale();
  const canWrite = useCan("operate");
  const [moving, setMoving] = useState<{ product?: Product; kind: Kind } | null>(null);
  const summary = useQuery({ queryKey: ["stock", "summary"], queryFn: () => api<StockSummary>("/stock/summary") });
  const low = useQuery({ queryKey: ["products", "low"], queryFn: () => api<Product[]>("/products?lowStock=true") });
  const movements = useQuery({ queryKey: ["stock", "movements"], queryFn: () => api<Movement[]>("/stock/movements?limit=50") });
  const s = summary.data;
  const cur = s?.currency ?? currency;
  const dt = new Intl.DateTimeFormat(locale, { dateStyle: "short", timeStyle: "short" });

  if (summary.isLoading) return <Spinner />;
  if (s && s.trackedProducts === 0) {
    return <Card><p className="text-sm text-muted-foreground">{t("stock.enableHint")}</p></Card>;
  }
  return (
    <div className="space-y-4">
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Stat label={t("stock.valueCost")} value={money(s?.valueAtCostMinor ?? 0, cur, locale)} hint={s?.withoutCost ? t("stock.withoutCost", { n: s.withoutCost }) : undefined} />
        <Stat label={t("stock.valuePrice")} value={money(s?.valueAtPriceMinor ?? 0, cur, locale)} />
        <Stat label={t("stock.units")} value={s?.units ?? 0} hint={t("stock.tracked", { n: s?.trackedProducts ?? 0 })} />
        <Stat label={t("stock.alerts")} value={(s?.lowStock ?? 0) + (s?.outOfStock ?? 0)} hint={t("stock.alertsHint", { low: s?.lowStock ?? 0, out: s?.outOfStock ?? 0 })} />
      </div>

      <div className="grid items-start gap-4 lg:grid-cols-2">
        <Card className="p-0">
          <div className="flex items-center justify-between gap-2 p-5 pb-3">
            <CardTitle>{t("stock.restock")}</CardTitle>
            {canWrite && <Button size="sm" variant="outline" onClick={() => setMoving({ kind: "PURCHASE" })}>{t("stock.newMovement")}</Button>}
          </div>
          {low.data?.length === 0 && <p className="px-5 pb-5 text-sm text-muted-foreground">{t("stock.nothingToRestock")}</p>}
          <ul className="divide-y divide-border">
            {low.data?.map((p) => (
              <li key={p.id} className="flex items-center gap-3 px-5 py-2.5 text-sm">
                <ProductPhoto url={p.photoUrl} name={p.name} className="h-10 w-10 shrink-0" />
                <span className="min-w-0 flex-1"><span className="block truncate font-medium">{p.name}</span>
                  <span className="text-xs text-muted-foreground">{p.minStock != null ? t("stock.min", { n: p.minStock }) : ""}</span></span>
                <Badge tone={p.stockQuantity <= 0 ? "danger" : "warning"}>{p.stockQuantity} {t(`pos.unitShort.${p.unit}`)}</Badge>
                {canWrite && <Button size="sm" onClick={() => setMoving({ product: p, kind: "PURCHASE" })}>{t("stock.kinds.PURCHASE")}</Button>}
              </li>
            ))}
          </ul>
        </Card>

        <Card className="p-0">
          <div className="p-5 pb-3"><CardTitle>{t("stock.recent")}</CardTitle></div>
          {movements.data?.length === 0 && <p className="px-5 pb-5 text-sm text-muted-foreground">{t("stock.noMovements")}</p>}
          <ul className="max-h-[28rem] divide-y divide-border overflow-y-auto">
            {movements.data?.map((m) => (
              <li key={m.id} className="flex items-start justify-between gap-3 px-5 py-2.5 text-sm">
                <span className="min-w-0">
                  <span className="block truncate font-medium">{m.productName}</span>
                  <span className="block text-xs text-muted-foreground">{t(`stock.kinds.${m.kind}`)} · {dt.format(new Date(m.createdAt))}{m.createdBy ? ` · ${m.createdBy}` : ""}</span>
                  {m.reason && <span className="block text-xs text-muted-foreground">{m.reason}</span>}
                </span>
                <span className="shrink-0 text-right tabular-nums">
                  <span className={m.quantity < 0 ? "text-[var(--danger)]" : "text-[var(--success)]"}>{m.quantity > 0 ? "+" : ""}{m.quantity}</span>
                  <span className="block text-xs text-muted-foreground">{t("stock.balance", { n: m.balanceAfter })}</span>
                </span>
              </li>
            ))}
          </ul>
        </Card>
      </div>
      {moving && <MovementDialog initial={moving} currency={cur} onClose={() => setMoving(null)} />}
    </div>
  );
}

function MovementDialog({ initial, currency, onClose }: { initial: { product?: Product; kind: Kind }; currency: string; onClose: () => void }) {
  const t = useTranslations("sales");
  const locale = useLocale();
  const qc = useQueryClient();
  const tracked = useQuery({ queryKey: ["products", "all"], queryFn: () => api<Product[]>("/products?includeInactive=true") });
  const [productId, setProductId] = useState(initial.product?.id ?? "");
  const [kind, setKind] = useState<Kind>(initial.kind);
  const [qty, setQty] = useState("");
  const product = tracked.data?.find((p) => p.id === productId) ?? initial.product;
  const [cost, setCost] = useState(toInput(initial.product?.costMinor));
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const n = Number(qty);
  const valid = productId && qty.trim() !== "" && Number.isInteger(n) && n >= 0 && (kind === "COUNT" || n > 0);
  const after = product ? (kind === "PURCHASE" ? product.stockQuantity + n : kind === "LOSS" ? product.stockQuantity - n : n) : null;

  async function save() {
    setBusy(true); setError(null);
    try {
      const unitCost = kind === "PURCHASE" && cost.trim() ? parseMoney(cost) : null;
      await api(`/products/${productId}/stock`, { body: { kind, quantity: n, unitCostMinor: unitCost, reason: reason.trim() || null } });
      await Promise.all(["products", "stock"].map((k) => qc.invalidateQueries({ queryKey: [k] })));
      onClose();
    } catch (e) { setError(errorMessage(e)); } finally { setBusy(false); }
  }

  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent title={t("stock.newMovement")}>
        <div className="space-y-3">
          <Field label={t("pos.product")} htmlFor="mv-p">
            <Select id="mv-p" value={productId} onChange={(e) => { setProductId(e.target.value); const p = tracked.data?.find((x) => x.id === e.target.value); setCost(toInput(p?.costMinor)); }}>
              <option value="">{t("pos.choose")}</option>
              {tracked.data?.filter((p) => p.trackStock).map((p) => <option key={p.id} value={p.id}>{p.name} ({p.stockQuantity})</option>)}
            </Select>
          </Field>
          <fieldset>
            <legend className="mb-1.5 text-sm font-medium">{t("stock.kind")}</legend>
            <div className="grid gap-2 sm:grid-cols-3" role="radiogroup">
              {(["PURCHASE", "LOSS", "COUNT"] as const).map((k) => (
                <button key={k} type="button" role="radio" aria-checked={kind === k} onClick={() => setKind(k)}
                  className={`rounded-lg border p-2 text-left text-sm ${kind === k ? "border-primary bg-accent" : "border-border hover:bg-muted"}`}>
                  <span className="block font-medium">{t(`stock.kinds.${k}`)}</span><span className="text-xs text-muted-foreground">{t(`stock.kindHints.${k}`)}</span>
                </button>
              ))}
            </div>
          </fieldset>
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label={kind === "COUNT" ? t("stock.counted") : t("stock.quantity")} htmlFor="mv-q"
              hint={after != null && qty.trim() ? t("stock.after", { n: after }) : undefined}>
              <Input id="mv-q" type="number" min={0} value={qty} onChange={(e) => setQty(e.target.value)} />
            </Field>
            {kind === "PURCHASE" && <Field label={t("stock.unitCost")} htmlFor="mv-c" hint={t("stock.unitCostHint")}>
              <Input id="mv-c" inputMode="decimal" value={cost} onChange={(e) => setCost(e.target.value)} placeholder={money(0, currency, locale)} />
            </Field>}
          </div>
          <Field label={t("stock.reason")} htmlFor="mv-r"><Input id="mv-r" maxLength={300} value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
          {error && <Alert tone="danger" title={error} />}
          <Button className="w-full" onClick={save} loading={busy} disabled={!valid}>{t("stock.save")}</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

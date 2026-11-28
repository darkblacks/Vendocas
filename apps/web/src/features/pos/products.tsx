"use client";
import { useMemo, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useLocale, useTranslations } from "next-intl";
import { Camera, Plus, Tags, Trash2 } from "lucide-react";
import { api, errorMessage } from "@/lib/api";
import { money } from "@/lib/format";
import { useCan } from "@/lib/auth";
import { Alert, Badge, Button, Card, Dialog, DialogContent, Field, Input, Select, Spinner, Switch, Textarea } from "@/components/ui";
import { UNITS, marginPct, parseMoney, toInput, type Category, type Product, type Unit } from "./types";
import { ProductPhoto } from "./product-photo";

/** Catalogue: search and filter, open a product to edit price, cost, category, barcode, stock settings and photo. */
export function ProductsTab({ currency }: { currency: string }) {
  const t = useTranslations("sales");
  const locale = useLocale();
  const canWrite = useCan("operate");
  const [q, setQ] = useState("");
  const [cat, setCat] = useState("");
  const [editing, setEditing] = useState<Product | "new" | null>(null);
  const products = useQuery({ queryKey: ["products", "all"], queryFn: () => api<Product[]>("/products?includeInactive=true") });
  const categories = useQuery({ queryKey: ["product-categories"], queryFn: () => api<Category[]>("/product-categories") });
  const shown = useMemo(() => {
    const s = q.trim().toLowerCase();
    return (products.data ?? []).filter((p) => (!cat || (cat === "-" ? !p.categoryId : p.categoryId === cat))
      && (!s || p.name.toLowerCase().includes(s) || p.sku?.toLowerCase().includes(s) || p.barcode?.includes(s)));
  }, [products.data, q, cat]);

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end gap-3">
        <div className="min-w-48 flex-1"><Field label={t("pos.searchProducts")} htmlFor="p-q"><Input id="p-q" value={q} onChange={(e) => setQ(e.target.value)} /></Field></div>
        <div className="w-48"><Field label={t("category")} htmlFor="p-cat">
          <Select id="p-cat" value={cat} onChange={(e) => setCat(e.target.value)}>
            <option value="">{t("pos.allCategories")}</option>
            {categories.data?.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
            <option value="-">{t("pos.noCategory")}</option>
          </Select>
        </Field></div>
        {canWrite && <CategoriesDialog />}
        {canWrite && <Button onClick={() => setEditing("new")}><Plus className="h-4 w-4" aria-hidden />{t("pos.newProduct")}</Button>}
      </div>

      <Card className="p-0">
        {products.isLoading && <div className="p-5"><Spinner /></div>}
        {products.data && shown.length === 0 && <p className="p-5 text-sm text-muted-foreground">{products.data.length ? t("pos.noMatch") : t("noProducts")}</p>}
        {shown.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[720px] text-sm">
              <thead className="text-left text-xs text-muted-foreground"><tr>
                {[t("pos.product"), t("category"), t("unitPrice"), t("pos.cost"), t("pos.margin"), t("pos.stock")].map((h, i) => (
                  <th key={h} scope="col" className={`px-4 py-2 font-medium ${i >= 2 ? "text-right" : ""}`}>{h}</th>))}
              </tr></thead>
              <tbody className="divide-y divide-border">
                {shown.map((p) => {
                  const m = marginPct(p.priceMinor, p.costMinor);
                  return (
                    <tr key={p.id} className={p.active ? "" : "opacity-60"}>
                      <td className="px-4 py-2">
                        <button type="button" className="flex items-center gap-3 text-left hover:text-primary" onClick={() => setEditing(p)}>
                          <ProductPhoto url={p.photoUrl} name={p.name} className="h-11 w-11 shrink-0" />
                          <span><span className="font-medium">{p.name}</span>
                            <span className="block text-xs text-muted-foreground">{[p.sku, p.barcode].filter(Boolean).join(" · ") || t(`pos.units.${p.unit}`)}
                              {!p.active && <> · {t("pos.inactive")}</>}</span></span>
                        </button>
                      </td>
                      <td className="px-4 py-2 text-muted-foreground">{p.category ?? "—"}</td>
                      <td className="px-4 py-2 text-right tabular-nums">{money(p.priceMinor, p.currency, locale)}</td>
                      <td className="px-4 py-2 text-right tabular-nums text-muted-foreground">{p.costMinor == null ? "—" : money(p.costMinor, p.currency, locale)}</td>
                      <td className="px-4 py-2 text-right tabular-nums">{m == null ? "—" : <span className={m < 0 ? "text-[var(--danger)]" : ""}>{m}%</span>}</td>
                      <td className="px-4 py-2 text-right">{p.trackStock
                        ? <Badge tone={p.stockQuantity <= 0 ? "danger" : p.lowStock ? "warning" : "neutral"}>{p.stockQuantity} {t(`pos.unitShort.${p.unit}`)}</Badge>
                        : <span className="text-xs text-muted-foreground">{t("pos.noStockControl")}</span>}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Card>
      {editing && <ProductEditor product={editing === "new" ? null : editing} currency={currency} categories={categories.data ?? []} onClose={() => setEditing(null)} />}
    </div>
  );
}

function ProductEditor({ product, currency, categories, onClose }: { product: Product | null; currency: string; categories: Category[]; onClose: () => void }) {
  const t = useTranslations("sales");
  const locale = useLocale();
  const qc = useQueryClient();
  const fileRef = useRef<HTMLInputElement>(null);
  const [f, setF] = useState({
    name: product?.name ?? "", price: toInput(product?.priceMinor), cost: toInput(product?.costMinor), categoryId: product?.categoryId ?? "",
    sku: product?.sku ?? "", barcode: product?.barcode ?? "", unit: (product?.unit ?? "UN") as Unit, description: product?.description ?? "",
    trackStock: product?.trackStock ?? false, minStock: product?.minStock?.toString() ?? "", initialStock: "", active: product?.active ?? true,
  });
  const [photo, setPhoto] = useState<File | null>(null);
  const [preview, setPreview] = useState<string | null>(product?.photoUrl ?? null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const price = parseMoney(f.price), cost = f.cost.trim() ? parseMoney(f.cost) : null;
  const m = marginPct(price || 0, cost);
  const valid = f.name.trim() && price >= 0 && !Number.isNaN(price) && f.price.trim() && (cost == null || !Number.isNaN(cost));

  function pick(file: File | undefined) {
    if (!file) return;
    if (!["image/jpeg", "image/png", "image/webp"].includes(file.type) || file.size > 5 * 1024 * 1024) { setError(t("pos.photoHint")); return; }
    setError(null); setPhoto(file); setPreview(URL.createObjectURL(file));
  }

  async function save() {
    setBusy(true); setError(null);
    try {
      const body = {
        name: f.name.trim(), priceMinor: price, currency: product?.currency ?? currency, categoryId: f.categoryId || null, costMinor: cost,
        sku: f.sku.trim() || null, barcode: f.barcode.trim() || null, unit: f.unit, description: f.description.trim() || null,
        trackStock: f.trackStock, minStock: f.trackStock && f.minStock.trim() ? Number(f.minStock) : null,
        initialStock: !product && f.trackStock && f.initialStock.trim() ? Number(f.initialStock) : null, active: f.active,
      };
      const r = product ? await api<{ id: string }>(`/products/${product.id}`, { method: "PUT", body }) : await api<{ id: string }>("/products", { body });
      if (photo) {
        const form = new FormData();
        form.append("file", photo);
        await api(`/products/${r.id}/photo`, { form });
      }
      await Promise.all(["products", "stock", "product-categories"].map((k) => qc.invalidateQueries({ queryKey: [k] })));
      onClose();
    } catch (e) { setError(errorMessage(e)); } finally { setBusy(false); }
  }

  async function removePhoto() {
    if (!product) { setPhoto(null); setPreview(null); return; }
    setBusy(true);
    try { await api(`/products/${product.id}/photo`, { method: "DELETE" }); setPreview(null); setPhoto(null); await qc.invalidateQueries({ queryKey: ["products"] }); }
    catch (e) { setError(errorMessage(e)); } finally { setBusy(false); }
  }

  return (
    <Dialog open onOpenChange={(o) => { if (!o) onClose(); }}>
      <DialogContent title={product ? t("pos.editProduct") : t("pos.newProduct")} className="max-w-2xl">
        <div className="grid gap-4 sm:grid-cols-[10rem_minmax(0,1fr)]">
          <div className="space-y-2">
            <ProductPhoto url={preview} name={f.name} className="aspect-square w-full" />
            <input ref={fileRef} type="file" accept="image/jpeg,image/png,image/webp" className="sr-only" aria-label={t("pos.photo")} onChange={(e) => pick(e.target.files?.[0])} />
            <Button variant="outline" size="sm" className="w-full" onClick={() => fileRef.current?.click()}><Camera className="h-4 w-4" aria-hidden />{preview ? t("pos.changePhoto") : t("pos.addPhoto")}</Button>
            {preview && <Button variant="ghost" size="sm" className="w-full" onClick={removePhoto} disabled={busy}>{t("pos.removePhoto")}</Button>}
            <p className="text-xs text-muted-foreground">{t("pos.photoHint")}</p>
          </div>
          <div className="grid gap-3 sm:grid-cols-2">
            <div className="sm:col-span-2"><Field label={t("name")} htmlFor="pe-name"><Input id="pe-name" maxLength={200} value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} /></Field></div>
            <Field label={t("unitPrice")} htmlFor="pe-price"><Input id="pe-price" inputMode="decimal" value={f.price} onChange={(e) => setF({ ...f, price: e.target.value })} placeholder="0,00" /></Field>
            <Field label={t("pos.cost")} htmlFor="pe-cost" hint={m == null ? t("pos.costHint") : t("pos.marginPct", { pct: m })}>
              <Input id="pe-cost" inputMode="decimal" value={f.cost} onChange={(e) => setF({ ...f, cost: e.target.value })} placeholder="0,00" />
            </Field>
            <Field label={t("category")} htmlFor="pe-cat">
              <Select id="pe-cat" value={f.categoryId} onChange={(e) => setF({ ...f, categoryId: e.target.value })}>
                <option value="">{t("pos.noCategory")}</option>
                {categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
              </Select>
            </Field>
            <Field label={t("pos.unit")} htmlFor="pe-unit">
              <Select id="pe-unit" value={f.unit} onChange={(e) => setF({ ...f, unit: e.target.value as Unit })}>
                {UNITS.map((u) => <option key={u} value={u}>{t(`pos.units.${u}`)}</option>)}
              </Select>
            </Field>
            <Field label={t("pos.barcode")} htmlFor="pe-bar"><Input id="pe-bar" inputMode="numeric" maxLength={50} value={f.barcode} onChange={(e) => setF({ ...f, barcode: e.target.value })} /></Field>
            <Field label="SKU" htmlFor="pe-sku"><Input id="pe-sku" maxLength={80} value={f.sku} onChange={(e) => setF({ ...f, sku: e.target.value })} /></Field>
            <div className="sm:col-span-2"><Field label={t("pos.description")} htmlFor="pe-desc"><Textarea id="pe-desc" rows={2} maxLength={1000} value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })} /></Field></div>
            <label className="flex items-center gap-3 text-sm sm:col-span-2"><Switch checked={f.trackStock} onCheckedChange={(v) => setF({ ...f, trackStock: v })} />{t("pos.trackStock")}</label>
            {f.trackStock && <>
              {!product && <Field label={t("pos.initialStock")} htmlFor="pe-init"><Input id="pe-init" type="number" min={0} value={f.initialStock} onChange={(e) => setF({ ...f, initialStock: e.target.value })} /></Field>}
              {product && <p className="self-end text-sm text-muted-foreground">{t("pos.currentStock", { n: product.stockQuantity })}</p>}
              <Field label={t("pos.minStock")} htmlFor="pe-min" hint={t("pos.minStockHint")}><Input id="pe-min" type="number" min={0} value={f.minStock} onChange={(e) => setF({ ...f, minStock: e.target.value })} /></Field>
            </>}
            <label className="flex items-center gap-3 text-sm sm:col-span-2"><Switch checked={f.active} onCheckedChange={(v) => setF({ ...f, active: v })} />{t("pos.activeHint")}</label>
          </div>
        </div>
        {error && <div className="mt-3"><Alert tone="danger" title={error} /></div>}
        <div className="mt-4 flex items-center justify-between gap-2">
          <span className="text-sm text-muted-foreground">{price > 0 && money(price, product?.currency ?? currency, locale)}</span>
          <Button onClick={save} loading={busy} disabled={!valid}>{t("pos.saveProduct")}</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

function CategoriesDialog() {
  const t = useTranslations("sales");
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const categories = useQuery({ queryKey: ["product-categories"], enabled: open, queryFn: () => api<Category[]>("/product-categories") });
  const refresh = () => Promise.all(["product-categories", "products"].map((k) => qc.invalidateQueries({ queryKey: [k] })));
  async function add() {
    setError(null);
    try { await api("/product-categories", { body: { name: name.trim() } }); setName(""); await refresh(); } catch (e) { setError(errorMessage(e)); }
  }
  async function remove(c: Category) {
    if (!confirm(t("pos.deleteCategory", { name: c.name }))) return;
    try { await api(`/product-categories/${c.id}`, { method: "DELETE" }); await refresh(); } catch (e) { setError(errorMessage(e)); }
  }
  return (
    <>
      <Button variant="outline" onClick={() => setOpen(true)}><Tags className="h-4 w-4" aria-hidden />{t("pos.categories")}</Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent title={t("pos.categories")} description={t("pos.categoriesHint")}>
          <form className="flex gap-2" onSubmit={(e) => { e.preventDefault(); if (name.trim()) void add(); }}>
            <Input aria-label={t("pos.categoryName")} placeholder={t("pos.categoryName")} maxLength={100} value={name} onChange={(e) => setName(e.target.value)} />
            <Button type="submit" disabled={!name.trim()}>{t("add")}</Button>
          </form>
          {error && <div className="mt-3"><Alert tone="danger" title={error} /></div>}
          <ul className="mt-3 divide-y divide-border rounded-lg border border-border text-sm">
            {categories.data?.map((c) => (
              <li key={c.id} className="flex items-center justify-between gap-2 px-3 py-1.5">
                <span>{c.name} <span className="text-xs text-muted-foreground">· {t("pos.productsCount", { n: c.products })}</span></span>
                <Button variant="ghost" size="icon" aria-label={t("pos.deleteCategory", { name: c.name })} onClick={() => remove(c)}><Trash2 className="h-4 w-4" /></Button>
              </li>
            ))}
            {categories.data?.length === 0 && <li className="px-3 py-2 text-muted-foreground">{t("pos.noCategories")}</li>}
          </ul>
        </DialogContent>
      </Dialog>
    </>
  );
}

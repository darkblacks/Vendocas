"use client";
import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useLocale, useTranslations } from "next-intl";
import { Plus, Trash2, UserPlus } from "lucide-react";
import { api, errorMessage } from "@/lib/api";
import { money } from "@/lib/format";
import { useCan } from "@/lib/auth";
import { Alert, Button, Card, Dialog, DialogContent, DialogTrigger, EmptyState, Field, Input, PageHeader, Spinner, Textarea } from "@/components/ui";
import { LockedScreen, useHasFeature } from "@/features/plan";

interface Persona { id: string; name: string; description?: string | null; color?: string | null; products: { id: string; name: string }[]; customers: number;
  averageTicketMinor?: number | null; currency?: string | null; purchases: number; topWeekday?: string | null; topProducts: string[] }
interface Product { id: string; name: string }
interface Row { id: string; name?: string | null; phone?: string | null; email?: string | null }

/** Personas: profiles of the store's customers, linked to products, with what each profile does (ticket, weekday, products). */
export default function PersonasPage() {
  const t = useTranslations("journey.personas");
  const locale = useLocale();
  const has = useHasFeature("PERSONAS");
  const canWrite = useCan("operate");
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ["personas"], enabled: !!has, queryFn: () => api<Persona[]>("/crm/personas") });
  if (has === undefined) return <Spinner />;
  if (!has) {
    return (
      <LockedScreen feature="PERSONAS" title={t("lockedTitle")} text={t("lockedText")}>
        <div className="grid gap-3 sm:grid-cols-3">
          {[t("example1"), t("example2"), t("example3")].map((x) => <div key={x} className="rounded-xl border border-border bg-card p-4 text-sm">{x}</div>)}
        </div>
      </LockedScreen>
    );
  }
  return (
    <div className="mx-auto max-w-6xl">
      <PageHeader title={t("title")} description={t("subtitle")} actions={canWrite && <PersonaDialog />} />
      {q.isLoading && <Spinner />}
      {q.data && q.data.length === 0 && <EmptyState title={t("emptyTitle")} description={t("emptyText")} />}
      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
        {q.data?.map((p) => (
          <Card key={p.id} className="flex flex-col gap-3">
            <div className="flex items-start justify-between gap-2">
              <div><h2 className="text-lg font-semibold">{p.name}</h2>{p.description && <p className="text-sm text-muted-foreground">{p.description}</p>}</div>
              {canWrite && <Button variant="ghost" size="icon" aria-label={t("delete", { name: p.name })} onClick={async () => {
                if (!confirm(t("deleteConfirm", { name: p.name }))) return;
                await api(`/crm/personas/${p.id}`, { method: "DELETE" }); await qc.invalidateQueries({ queryKey: ["personas"] });
              }}><Trash2 className="h-4 w-4" /></Button>}
            </div>
            <dl className="grid grid-cols-2 gap-2 rounded-lg bg-muted p-3 text-center text-sm">
              <div><dt className="text-xs text-muted-foreground">{t("customers")}</dt><dd className="text-lg font-semibold tabular-nums">{p.customers}</dd></div>
              <div><dt className="text-xs text-muted-foreground">{t("ticket")}</dt><dd className="text-lg font-semibold tabular-nums">{p.averageTicketMinor != null ? money(p.averageTicketMinor, p.currency ?? "BRL", locale) : "—"}</dd></div>
            </dl>
            <ul className="space-y-1 text-sm">
              <li>{t("buysOn")}: <b>{p.topWeekday ? t(`days.${p.topWeekday}`) : "—"}</b></li>
              <li>{t("buys")}: <b>{p.topProducts.length ? p.topProducts.join(", ") : "—"}</b></li>
              <li>{t("linked")}: {p.products.length ? p.products.map((x) => x.name).join(", ") : "—"}</li>
            </ul>
            {canWrite && <AssignDialog persona={p} />}
          </Card>
        ))}
      </div>
      <p className="mt-6 text-xs text-muted-foreground">{t("weatherSoon")}</p>
    </div>
  );
}

function PersonaDialog() {
  const t = useTranslations("journey.personas");
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  const [f, setF] = useState({ name: "", description: "", productIds: [] as string[] });
  const [error, setError] = useState<string | null>(null);
  const products = useQuery({ queryKey: ["products"], enabled: open, queryFn: () => api<Product[]>("/products") });
  async function save() {
    setError(null);
    try {
      await api("/crm/personas", { body: { name: f.name, description: f.description || null, productIds: f.productIds } });
      await qc.invalidateQueries({ queryKey: ["personas"] });
      setOpen(false); setF({ name: "", description: "", productIds: [] });
    } catch (e) { setError(errorMessage(e)); }
  }
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild><Button><Plus className="h-4 w-4" aria-hidden />{t("new")}</Button></DialogTrigger>
      <DialogContent title={t("new")} description={t("newText")}>
        <div className="space-y-3">
          <Field label={t("name")} htmlFor="p-name"><Input id="p-name" value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} placeholder={t("namePlaceholder")} /></Field>
          <Field label={t("description")} htmlFor="p-desc"><Textarea id="p-desc" value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })} /></Field>
          <fieldset>
            <legend className="mb-1.5 text-sm font-medium">{t("products")}</legend>
            {products.data?.length ? (
              <div className="max-h-48 space-y-1 overflow-y-auto rounded-lg border border-border p-2">
                {products.data.map((p) => (
                  <label key={p.id} className="flex min-h-8 items-center gap-2 text-sm">
                    <input type="checkbox" className="h-4 w-4 accent-[var(--primary)]" checked={f.productIds.includes(p.id)}
                      onChange={(e) => setF({ ...f, productIds: e.target.checked ? [...f.productIds, p.id] : f.productIds.filter((x) => x !== p.id) })} />{p.name}
                  </label>
                ))}
              </div>
            ) : <p className="text-sm text-muted-foreground">{t("noProducts")}</p>}
          </fieldset>
          {error && <Alert tone="danger" title={error} />}
          <Button onClick={save} disabled={!f.name.trim()}>{t("save")}</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

function AssignDialog({ persona }: { persona: Persona }) {
  const t = useTranslations("journey.personas");
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState("");
  const [msg, setMsg] = useState<string | null>(null);
  const found = useQuery({ queryKey: ["crm", "pick", q], enabled: open && q.trim().length >= 2,
    queryFn: () => api<{ items: Row[] }>(`/crm/customers?size=8&q=${encodeURIComponent(q.trim())}`) });
  async function add(c: Row) {
    await api(`/crm/personas/${persona.id}/customers`, { body: { contactIds: [c.id] } });
    setMsg(t("added", { name: c.name || c.phone || c.email || "" }));
    await qc.invalidateQueries({ queryKey: ["personas"] });
  }
  return (
    <Dialog open={open} onOpenChange={(o) => { setOpen(o); setMsg(null); }}>
      <DialogTrigger asChild><Button variant="outline" size="sm" className="mt-auto"><UserPlus className="h-4 w-4" aria-hidden />{t("assign")}</Button></DialogTrigger>
      <DialogContent title={t("assignTitle", { name: persona.name })}>
        <div className="space-y-3">
          <Input aria-label={t("search")} placeholder={t("search")} value={q} onChange={(e) => setQ(e.target.value)} />
          <ul className="divide-y divide-border rounded-lg border border-border">
            {found.data?.items.map((c) => (
              <li key={c.id}><button type="button" className="flex min-h-10 w-full items-center justify-between px-3 text-left text-sm hover:bg-muted" onClick={() => void add(c)}>
                <span>{c.name || "—"}</span><span className="text-xs text-muted-foreground">{c.phone ?? c.email}</span>
              </button></li>
            ))}
          </ul>
          {msg && <Alert tone="success" title={msg} />}
        </div>
      </DialogContent>
    </Dialog>
  );
}

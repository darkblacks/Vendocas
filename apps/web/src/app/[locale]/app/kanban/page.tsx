"use client";
import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useLocale, useTranslations } from "next-intl";
import { Plus, Star } from "lucide-react";
import { api, errorMessage } from "@/lib/api";
import { money, toMinor } from "@/lib/format";
import { useCan } from "@/lib/auth";
import { Link } from "@/i18n/navigation";
import { Alert, Button, Dialog, DialogContent, DialogTrigger, Field, Input, PageHeader, Spinner, Textarea, cn } from "@/components/ui";
import { LockedScreen, useHasFeature } from "@/features/plan";
import { MARKET_CURRENCY, type Stage, type UsageView } from "@/features/markets";

interface Card { id: string; name?: string | null; email?: string | null; phone?: string | null; vip: boolean; orders: number; totalSpentMinor: number;
  currency?: string | null; lastPurchaseAt?: string | null; nextExpectedAt?: string | null }
interface Column { stage: Stage; count: number; cards: Card[] }
interface Deal { id: string; title: string; companyName?: string | null; contactName?: string | null; stage: string; valueMinor?: number | null; currency?: string | null }
interface DealColumn { stage: string; count: number; valueMinor: number; deals: Deal[] }

const DEAL_STAGES = ["LEAD", "PROPOSAL", "NEGOTIATION", "WON", "LOST"] as const;

/** Kanban: B2C (automatic, from the PDV) and B2B (deals moved by hand). Plan Alerta + Vendas. */
export default function KanbanPage() {
  const t = useTranslations("journey.kanban");
  const has = useHasFeature("PIPELINE");
  const [mode, setMode] = useState<"B2C" | "B2B">("B2C");
  if (has === undefined) return <Spinner />;
  if (!has) {
    return (
      <LockedScreen feature="PIPELINE" title={t("lockedTitle")} text={t("lockedText")}>
        <div className="grid grid-cols-5 gap-3">{(["FIRST_SALE", "RECURRING", "IN_WINDOW", "CHURN_RISK", "CHURNED"] as const).map((s) =>
          <div key={s} className="h-48 rounded-xl bg-muted p-3 text-sm font-semibold">{t(`b2c.${s}`)}</div>)}</div>
      </LockedScreen>
    );
  }
  return (
    <div>
      <PageHeader title={t("title")} description={mode === "B2C" ? t("b2cText") : t("b2bText")}
        actions={mode === "B2B" ? <NewDeal /> : undefined} />
      <div role="tablist" aria-label={t("mode")} className="mb-4 inline-flex rounded-lg bg-muted p-1">
        {(["B2C", "B2B"] as const).map((m) => (
          <button key={m} role="tab" aria-selected={mode === m} onClick={() => setMode(m)}
            className={cn("min-h-9 rounded-md px-4 text-sm", mode === m ? "bg-card font-semibold shadow-sm" : "text-muted-foreground")}>{t(`tabs.${m}`)}</button>
        ))}
      </div>
      {mode === "B2C" ? <B2C /> : <B2B />}
    </div>
  );
}

function B2C() {
  const t = useTranslations("journey.kanban");
  const locale = useLocale();
  const q = useQuery({ queryKey: ["crm", "kanban"], queryFn: () => api<Column[]>("/crm/kanban?perColumn=30") });
  if (q.isLoading) return <Spinner />;
  if (q.error) return <Alert tone="danger" title={errorMessage(q.error)} />;
  const days = (iso?: string | null) => (iso ? Math.round((new Date(iso).getTime() - Date.now()) / 86_400_000) : null);
  return (
    <>
      <div className="flex gap-3 overflow-x-auto pb-3">
        {q.data!.map((col) => (
          <section key={col.stage} aria-label={t(`b2c.${col.stage}`)} className="flex w-60 shrink-0 flex-col gap-2 rounded-xl bg-muted/60 p-3 xl:w-auto xl:min-w-0 xl:flex-1">
            <header className="mb-1">
              <div className="flex items-center justify-between text-sm font-semibold"><span>{t(`b2c.${col.stage}`)}</span><span className="tabular-nums text-muted-foreground">{col.count}</span></div>
              <p className="text-xs text-muted-foreground">{t(`b2cHint.${col.stage}`)}</p>
            </header>
            {col.cards.map((c) => {
              const d = days(c.nextExpectedAt);
              return (
                <Link key={c.id} href={`/app/contacts/${c.id}`} className="rounded-lg border border-border bg-card p-3 text-sm hover:border-primary">
                  <p className="flex items-center gap-1 font-medium">{c.name || c.phone || c.email || "—"}{c.vip && <Star className="h-3.5 w-3.5 fill-[var(--brand-orange-light)] text-[var(--brand-orange-light)]" aria-label="VIP" />}</p>
                  <p className="text-xs text-muted-foreground">{t("purchases", { n: c.orders })} · {money(c.totalSpentMinor, c.currency ?? undefined, locale)}</p>
                  {d != null && <p className={cn("mt-1 text-xs", d < 0 ? "font-medium text-[var(--warning)]" : "text-muted-foreground")}>{d >= 0 ? t("returnsIn", { n: d }) : t("late", { n: -d })}</p>}
                </Link>
              );
            })}
            {col.count > col.cards.length && <Link href={`/app/contacts?stage=${col.stage}`} className="text-center text-xs text-primary">{t("seeAll", { n: col.count })}</Link>}
          </section>
        ))}
      </div>
      <p className="text-xs text-muted-foreground">{t("b2cAuto")}</p>
    </>
  );
}

function B2B() {
  const t = useTranslations("journey.kanban");
  const locale = useLocale();
  const qc = useQueryClient();
  const canWrite = useCan("operate");
  const q = useQuery({ queryKey: ["deals"], queryFn: () => api<DealColumn[]>("/crm/deals") });
  const [dragging, setDragging] = useState<string | null>(null);
  const [over, setOver] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  async function move(id: string, stage: string, index?: number) {
    setError(null);
    let lostReason: string | null = null;
    if (stage === "LOST") lostReason = prompt(t("lostReason")) ?? null;
    try { await api(`/crm/deals/${id}/move`, { body: { stage, index: index ?? null, lostReason } }); await qc.invalidateQueries({ queryKey: ["deals"] }); }
    catch (e) { setError(errorMessage(e)); }
  }
  if (q.isLoading) return <Spinner />;
  if (q.error) return <Alert tone="danger" title={errorMessage(q.error)} />;
  return (
    <>
      {error && <div className="mb-3"><Alert tone="danger" title={error} /></div>}
      <div className="flex gap-3 overflow-x-auto pb-3">
        {q.data!.map((col) => (
          <section key={col.stage} aria-label={t(`b2b.${col.stage}`)}
            onDragOver={(e) => { if (canWrite) { e.preventDefault(); setOver(col.stage); } }} onDragLeave={() => setOver(null)}
            onDrop={(e) => { e.preventDefault(); setOver(null); if (dragging) void move(dragging, col.stage); setDragging(null); }}
            className={cn("flex min-h-64 w-60 shrink-0 flex-col gap-2 rounded-xl bg-muted/60 p-3 xl:w-auto xl:min-w-0 xl:flex-1", over === col.stage && "ring-2 ring-primary")}>
            <header className="mb-1">
              <div className="flex items-center justify-between text-sm font-semibold"><span>{t(`b2b.${col.stage}`)}</span><span className="tabular-nums text-muted-foreground">{col.count}</span></div>
              <p className="text-xs tabular-nums text-muted-foreground">{money(col.valueMinor, col.deals[0]?.currency ?? "BRL", locale)}</p>
            </header>
            {col.deals.map((d) => (
              <article key={d.id} draggable={canWrite} onDragStart={() => setDragging(d.id)} onDragEnd={() => setDragging(null)}
                className={cn("rounded-lg border border-border bg-card p-3 text-sm", canWrite && "cursor-grab active:cursor-grabbing", dragging === d.id && "opacity-50")}>
                <p className="font-medium">{d.title}</p>
                <p className="text-xs text-muted-foreground">{[d.companyName, d.contactName].filter(Boolean).join(" · ") || "—"}</p>
                {d.valueMinor != null && <p className="mt-1 text-xs font-semibold tabular-nums">{money(d.valueMinor, d.currency ?? "BRL", locale)}</p>}
                {canWrite && (
                  <label className="mt-2 block text-xs text-muted-foreground">
                    <span className="sr-only">{t("moveTo")}</span>
                    <select className="w-full rounded-md border border-border bg-card px-1 py-1 text-xs" value={d.stage} onChange={(e) => void move(d.id, e.target.value)}>
                      {DEAL_STAGES.map((s) => <option key={s} value={s}>{t("moveOption", { stage: t(`b2b.${s}`) })}</option>)}
                    </select>
                  </label>
                )}
              </article>
            ))}
          </section>
        ))}
      </div>
      <p className="text-xs text-muted-foreground">{t("b2bHint")}</p>
    </>
  );
}

function NewDeal() {
  const t = useTranslations("journey.kanban");
  const qc = useQueryClient();
  const usage = useQuery({ queryKey: ["usage"], queryFn: () => api<UsageView>("/billing/usage") });
  const [open, setOpen] = useState(false);
  const [f, setF] = useState({ title: "", company: "", value: "", notes: "" });
  const [error, setError] = useState<string | null>(null);
  async function save() {
    setError(null);
    try {
      const v = toMinor(f.value);
      await api("/crm/deals", { body: { title: f.title, companyName: f.company || null, valueMinor: f.value ? v : null,
        currency: usage.data ? MARKET_CURRENCY[usage.data.market] : "BRL", notes: f.notes || null } });
      await qc.invalidateQueries({ queryKey: ["deals"] });
      setOpen(false); setF({ title: "", company: "", value: "", notes: "" });
    } catch (e) { setError(errorMessage(e)); }
  }
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild><Button><Plus className="h-4 w-4" aria-hidden />{t("newDeal")}</Button></DialogTrigger>
      <DialogContent title={t("newDeal")}>
        <div className="space-y-3">
          <Field label={t("dealTitle")} htmlFor="d-title"><Input id="d-title" value={f.title} onChange={(e) => setF({ ...f, title: e.target.value })} /></Field>
          <Field label={t("company")} htmlFor="d-co"><Input id="d-co" value={f.company} onChange={(e) => setF({ ...f, company: e.target.value })} /></Field>
          <Field label={t("value")} htmlFor="d-val"><Input id="d-val" inputMode="decimal" value={f.value} onChange={(e) => setF({ ...f, value: e.target.value })} /></Field>
          <Field label={t("notes")} htmlFor="d-notes"><Textarea id="d-notes" value={f.notes} onChange={(e) => setF({ ...f, notes: e.target.value })} /></Field>
          {error && <Alert tone="danger" title={error} />}
          <Button onClick={save} disabled={!f.title.trim()}>{t("create")}</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

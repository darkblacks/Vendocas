"use client";
import { useState } from "react";
import { useSearchParams } from "next/navigation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useLocale, useTranslations } from "next-intl";
import { Link } from "@/i18n/navigation";
import { api, ApiError, errorMessage } from "@/lib/api";
import { date, money } from "@/lib/format";
import { useCan } from "@/lib/auth";
import { STAGES, STAGE_TONE, type Stage } from "@/features/markets";
import { Alert, Badge, Button, Dialog, DialogContent, DialogTrigger, EmptyState, Field, Input, PageHeader, Select, Spinner, cn } from "@/components/ui";

interface CustomerRow {
  id: string; name?: string | null; email?: string | null; phone?: string | null; stage: Stage; recency?: number | null; frequency?: number | null;
  monetary?: number | null; orders: number; totalSpentMinor: number; currency?: string | null; lastPurchaseAt?: string | null; channels: string[]; tags: string[];
}
interface CrmPage { items: CustomerRow[]; total: number; page: number; size: number }
interface KanbanColumn { stage: Stage; count: number; cards: CustomerRow[] }

const SORTS = ["lastPurchase", "totalSpent", "orders", "rfm", "name", "created"] as const;

/** Clientes: the store's customers as a spreadsheet or a kanban by automatic stage. */
export default function CustomersPage() {
  const t = useTranslations();
  const canWrite = useCan("operate");
  const params = useSearchParams();
  const initialStage = params.get("stage");
  const [view, setView] = useState<"table" | "kanban">("table");
  const [stage, setStage] = useState<string>(initialStage && (STAGES as readonly string[]).includes(initialStage) ? initialStage : "");
  const [channel, setChannel] = useState("");
  const [q, setQ] = useState("");
  const [sort, setSort] = useState<(typeof SORTS)[number]>("lastPurchase");
  const [page, setPage] = useState(0);
  const qc = useQueryClient();
  const [recomputing, setRecomputing] = useState(false);

  return (
    <div>
      <PageHeader title={t("crm.title")} description={t("crm.subtitle")} actions={canWrite && (<>
        <Button variant="outline" asChild><Link href="/app/contacts/import">{t("contacts.import")}</Link></Button>
        <CreateContact />
      </>)} />
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <div role="tablist" aria-label={t("crm.view")} className="inline-flex rounded-lg bg-muted p-1">
          {(["table", "kanban"] as const).map((v) => (
            <button key={v} role="tab" aria-selected={view === v} onClick={() => setView(v)}
              className={cn("min-h-9 rounded-md px-3 text-sm", view === v ? "bg-card font-medium shadow-sm" : "text-muted-foreground")}>
              {t(`crm.${v}`)}
            </button>
          ))}
        </div>
        {view === "table" && (<>
          <Input className="min-w-56 flex-1" placeholder={t("crm.search")} aria-label={t("common.search")} value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} />
          <Select aria-label={t("crm.stage")} className="w-44" value={stage} onChange={(e) => { setStage(e.target.value); setPage(0); }}>
            <option value="">{t("crm.allStages")}</option>
            {STAGES.map((s) => <option key={s} value={s}>{t(`stages.${s}`)}</option>)}
          </Select>
          <Select aria-label={t("crm.channel")} className="w-44" value={channel} onChange={(e) => { setChannel(e.target.value); setPage(0); }}>
            <option value="">{t("crm.anyChannel")}</option>
            {["WHATSAPP", "EMAIL", "SMS"].map((c) => <option key={c} value={c}>{t("crm.accepts", { channel: t(`markets.channels.${c}`) })}</option>)}
          </Select>
          <Select aria-label={t("crm.sortBy")} className="w-48" value={sort} onChange={(e) => setSort(e.target.value as (typeof SORTS)[number])}>
            {SORTS.map((s) => <option key={s} value={s}>{t(`crm.sort.${s}`)}</option>)}
          </Select>
        </>)}
        {canWrite && <Button variant="ghost" size="sm" loading={recomputing} onClick={async () => {
          setRecomputing(true);
          try { await api("/crm/recompute", { method: "POST" }); setTimeout(() => qc.invalidateQueries({ queryKey: ["crm"] }), 3000); } finally { setRecomputing(false); }
        }}>{t("crm.recompute")}</Button>}
      </div>
      {view === "table"
        ? <CustomerTable stage={stage} channel={channel} q={q} sort={sort} page={page} setPage={setPage} />
        : <Kanban />}
      <p className="mt-4 text-xs text-muted-foreground">{t("crm.autoStageNote")}</p>
    </div>
  );
}

function StageBadge({ stage }: { stage: Stage }) {
  const t = useTranslations("stages");
  return <Badge tone={STAGE_TONE[stage]} className={stage === "VIP" ? "font-semibold" : undefined}>{t(stage)}</Badge>;
}

function CustomerTable({ stage, channel, q, sort, page, setPage }: { stage: string; channel: string; q: string; sort: string; page: number; setPage: (n: number) => void }) {
  const t = useTranslations();
  const locale = useLocale();
  const params = new URLSearchParams({ sort, dir: sort === "name" ? "asc" : "desc", page: String(page), size: "50" });
  if (stage) params.set("stage", stage);
  if (channel) params.set("channel", channel);
  if (q) params.set("q", q);
  const query = useQuery({ queryKey: ["crm", "customers", params.toString()], queryFn: () => api<CrmPage>(`/crm/customers?${params}`) });
  if (query.isLoading) return <Spinner />;
  if (query.error) return <Alert tone="danger" title={errorMessage(query.error)} />;
  const d = query.data!;
  if (d.items.length === 0) return <EmptyState title={t("common.noData")} />;
  const pages = Math.max(1, Math.ceil(d.total / d.size));
  return (
    <>
      <div className="overflow-x-auto rounded-lg border border-border bg-card">
        <table className="w-full min-w-[900px] text-sm">
          <thead className="bg-muted/60 text-left text-xs text-muted-foreground">
            <tr>{["name", "stage", "rfm", "lastPurchase", "totalSpent", "orders", "channels", "tags"].map((h) => <th key={h} scope="col" className="whitespace-nowrap px-3 py-2.5 font-medium">{t(`crm.col.${h}`)}</th>)}</tr>
          </thead>
          <tbody className="divide-y divide-border">
            {d.items.map((c) => (
              <tr key={c.id} className="hover:bg-muted/40">
                <td className="px-3 py-2.5">
                  <Link href={`/app/contacts/${c.id}`} className="font-medium hover:text-primary">{c.name || c.email || c.phone || "—"}</Link>
                  <div className="text-xs text-muted-foreground">{[c.email, c.phone].filter(Boolean).join(" · ")}</div>
                </td>
                <td className="px-3 py-2.5"><StageBadge stage={c.stage} /></td>
                <td className="px-3 py-2.5 tabular-nums" title={t("crm.rfmHint")}>{c.recency ? `${c.recency}-${c.frequency}-${c.monetary}` : "—"}</td>
                <td className="whitespace-nowrap px-3 py-2.5">{date(c.lastPurchaseAt, locale)}</td>
                <td className="whitespace-nowrap px-3 py-2.5 tabular-nums">{c.totalSpentMinor ? money(c.totalSpentMinor, c.currency, locale) : "—"}</td>
                <td className="px-3 py-2.5 tabular-nums">{c.orders}</td>
                <td className="px-3 py-2.5 text-xs">{c.channels.map((ch) => t(`markets.channels.${ch}`)).join(", ") || "—"}</td>
                <td className="px-3 py-2.5"><div className="flex flex-wrap gap-1">{c.tags.map((tg) => <Badge key={tg}>{tg}</Badge>)}</div></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="mt-3 flex items-center justify-between text-sm text-muted-foreground">
        <span>{t("crm.total", { n: d.total.toLocaleString(locale) })}</span>
        <div className="flex items-center gap-2">
          <span className="tabular-nums">{page + 1} / {pages}</span>
          <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage(page - 1)}>{t("common.back")}</Button>
          <Button variant="outline" size="sm" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>{t("common.next")}</Button>
        </div>
      </div>
    </>
  );
}

function Kanban() {
  const t = useTranslations();
  const locale = useLocale();
  const query = useQuery({ queryKey: ["crm", "kanban"], queryFn: () => api<KanbanColumn[]>("/crm/kanban?perColumn=25") });
  if (query.isLoading) return <Spinner />;
  if (query.error) return <Alert tone="danger" title={errorMessage(query.error)} />;
  return (
    <div className="flex gap-3 overflow-x-auto pb-2">
      {query.data!.map((col) => (
        <section key={col.stage} aria-label={t(`stages.${col.stage}`)} className="flex w-60 shrink-0 flex-col gap-2 rounded-lg bg-muted/60 p-3">
          <header className="flex items-center justify-between text-sm font-semibold">
            <span>{t(`stages.${col.stage}`)}</span><span className="tabular-nums text-muted-foreground">{col.count.toLocaleString(locale)}</span>
          </header>
          {col.cards.map((c) => (
            <Link key={c.id} href={`/app/contacts/${c.id}`} className="rounded-md border border-border bg-card p-3 text-sm hover:border-primary">
              <p className="font-medium">{c.name || c.email || c.phone || "—"}</p>
              <p className="text-xs text-muted-foreground">
                {c.orders > 0 ? `${t("crm.ordersCount", { n: c.orders })} · ${money(c.totalSpentMinor, c.currency, locale)}` : t("crm.noPurchases")}
              </p>
              {c.lastPurchaseAt && <p className="text-xs text-muted-foreground">{t("crm.lastPurchaseOn", { date: date(c.lastPurchaseAt, locale) })}</p>}
            </Link>
          ))}
          {col.count > col.cards.length && <p className="text-center text-xs text-muted-foreground">{t("crm.more", { n: (col.count - col.cards.length).toLocaleString(locale) })}</p>}
        </section>
      ))}
    </div>
  );
}

function CreateContact() {
  const t = useTranslations();
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState({ name: "", email: "", phone: "", tags: "" });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  async function save() {
    setBusy(true); setError(null);
    try {
      await api("/contacts", { body: { name: form.name || null, email: form.email || null, phone: form.phone || null,
        tags: form.tags.split(",").map((s) => s.trim()).filter(Boolean) } });
      await qc.invalidateQueries({ queryKey: ["crm"] });
      setOpen(false); setForm({ name: "", email: "", phone: "", tags: "" });
    } catch (e) { setError(e instanceof ApiError && e.fields.phone ? "Invalid phone number" : errorMessage(e)); } finally { setBusy(false); }
  }
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild><Button>{t("contacts.add")}</Button></DialogTrigger>
      <DialogContent title={t("contacts.add")} description={t("contacts.subtitle")}>
        <div className="space-y-3">
          <Field label={t("common.name")} htmlFor="cn"><Input id="cn" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></Field>
          <Field label={t("common.email")} htmlFor="ce"><Input id="ce" type="email" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} /></Field>
          <Field label={t("common.phone")} htmlFor="cp" hint="+55 11 98765-4321 / +1 212 555 0100"><Input id="cp" type="tel" value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} /></Field>
          <Field label={t("contacts.tags")} htmlFor="ct" hint={t("contacts.testRecipientHelp")}><Input id="ct" value={form.tags} onChange={(e) => setForm({ ...form, tags: e.target.value })} /></Field>
          {error && <Alert tone="danger" title={error} />}
          <Button onClick={save} loading={busy}>{t("common.save")}</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

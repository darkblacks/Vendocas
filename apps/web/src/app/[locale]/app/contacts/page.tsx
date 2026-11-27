"use client";
import { useState } from "react";
import { useSearchParams } from "next/navigation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useLocale, useTranslations } from "next-intl";
import { Star } from "lucide-react";
import { Link } from "@/i18n/navigation";
import { api, ApiError, errorMessage } from "@/lib/api";
import { date, money } from "@/lib/format";
import { useCan } from "@/lib/auth";
import { STAGES, STAGE_TONE, type Stage } from "@/features/markets";
import { useHasFeature } from "@/features/plan";
import { Alert, Badge, Button, Card, EmptyState, Field, Input, PageHeader, Select, Spinner, cn } from "@/components/ui";

interface CustomerRow {
  id: string; name?: string | null; email?: string | null; phone?: string | null; stage: Stage; orders: number; totalSpentMinor: number;
  currency?: string | null; lastPurchaseAt?: string | null; channels: string[]; vip: boolean; whatsappStatus: "YES" | "NO" | "UNKNOWN"; personas: string[];
}
interface CrmPage { items: CustomerRow[]; total: number; page: number; size: number }

const ALERT_PURPOSES = ["TRANSACTIONAL", "SERVICE_REMINDER", "MARKETING", "REACTIVATION"] as const;

/** Meus clientes: add a customer (name, phone, has WhatsApp?, e-mail, where they accept messages) and the list. */
export default function CustomersPage() {
  const t = useTranslations("journey.customers");
  const canWrite = useCan("operate");
  const pipeline = useHasFeature("PIPELINE");
  const params = useSearchParams();
  const initialStage = params.get("stage");
  const [stage, setStage] = useState(initialStage && (STAGES as readonly string[]).includes(initialStage) ? initialStage : "");
  const [wa, setWa] = useState("");
  const [q, setQ] = useState("");
  const [page, setPage] = useState(0);
  return (
    <div className="mx-auto max-w-7xl">
      <PageHeader title={t("title")} description={t("subtitle")} actions={canWrite && <Button variant="outline" asChild><Link href="/app/contacts/import">{t("import")}</Link></Button>} />
      <div className="grid items-start gap-5 lg:grid-cols-[minmax(0,22rem)_minmax(0,1fr)]">
        {canWrite ? <AddCustomer /> : <div />}
        <div className="min-w-0 space-y-3">
          <div className="flex flex-wrap gap-2">
            <Input className="min-w-52 flex-1" placeholder={t("search")} aria-label={t("search")} value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} />
            <Select aria-label={t("whatsappFilter")} className="w-56" value={wa} onChange={(e) => { setWa(e.target.value); setPage(0); }}>
              <option value="">{t("whatsappAny")}</option>
              {(["YES", "NO", "UNKNOWN"] as const).map((s) => <option key={s} value={s}>{t(`wa.${s}`)}</option>)}
            </Select>
            {pipeline && (
              <Select aria-label={t("stageFilter")} className="w-48" value={stage} onChange={(e) => { setStage(e.target.value); setPage(0); }}>
                <option value="">{t("stageAny")}</option>
                {STAGES.map((s) => <option key={s} value={s}>{t(`stages.${s}`)}</option>)}
              </Select>
            )}
          </div>
          <List stage={stage} wa={wa} q={q} page={page} setPage={setPage} showStage={!!pipeline} />
        </div>
      </div>
    </div>
  );
}

function AddCustomer() {
  const t = useTranslations("journey.customers");
  const qc = useQueryClient();
  const empty = { name: "", phone: "", email: "", whatsapp: "YES" as "YES" | "NO" | "UNKNOWN", acceptWhatsapp: true, acceptEmail: true };
  const [f, setF] = useState(empty);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ tone: "success" | "danger"; text: string } | null>(null);
  async function save() {
    setBusy(true); setMsg(null);
    try {
      const c = await api<{ id: string }>("/contacts", { body: { name: f.name || null, email: f.email || null, phone: f.phone || null, tags: [] } });
      if (f.phone) await api(`/crm/customers/${c.id}/whatsapp`, { method: "PUT", body: { status: f.whatsapp } });
      const grants: string[] = [];
      if (f.phone && f.whatsapp !== "NO" && f.acceptWhatsapp) grants.push("WHATSAPP");
      if (f.email && f.acceptEmail) grants.push("EMAIL");
      for (const channel of grants) {
        for (const purpose of ALERT_PURPOSES) {
          await api(`/contacts/${c.id}/consents`, { method: "PUT", body: { channel, purpose, status: "GRANTED", legalBasis: "CONSENT", source: "MANUAL",
            evidence: t("consentEvidence") } });
        }
      }
      await qc.invalidateQueries({ queryKey: ["crm"] });
      setMsg({ tone: "success", text: t("saved", { name: f.name || f.phone || f.email }) });
      setF(empty);
    } catch (e) {
      setMsg({ tone: "danger", text: e instanceof ApiError && e.status === 409 ? t("duplicate") : e instanceof ApiError && e.fields.phone ? t("badPhone") : errorMessage(e) });
    } finally { setBusy(false); }
  }
  return (
    <Card className="space-y-4">
      <h2 className="text-lg font-semibold">{t("add")}</h2>
      <Field label={t("name")} htmlFor="c-name"><Input id="c-name" value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} placeholder="Maria Souza" /></Field>
      <Field label={t("phone")} htmlFor="c-phone"><Input id="c-phone" type="tel" value={f.phone} onChange={(e) => setF({ ...f, phone: e.target.value })} placeholder="(11) 98765-4321" /></Field>
      {f.phone && (
        <fieldset>
          <legend className="mb-1.5 text-sm font-medium">{t("hasWhatsapp")}</legend>
          <div className="grid grid-cols-3 gap-2" role="radiogroup">
            {(["YES", "NO", "UNKNOWN"] as const).map((s) => (
              <button key={s} type="button" role="radio" aria-checked={f.whatsapp === s} onClick={() => setF({ ...f, whatsapp: s })}
                className={cn("min-h-10 rounded-lg border text-sm", f.whatsapp === s ? "border-primary bg-primary/10 font-semibold" : "border-border hover:bg-muted")}>{t(`wa.${s}`)}</button>
            ))}
          </div>
        </fieldset>
      )}
      <Field label={t("email")} htmlFor="c-email"><Input id="c-email" type="email" value={f.email} onChange={(e) => setF({ ...f, email: e.target.value })} placeholder="maria@email.com" /></Field>
      {(f.phone || f.email) && (
        <fieldset className="space-y-2 text-sm">
          <legend className="mb-1.5 font-medium">{t("accepts")}</legend>
          {f.phone && f.whatsapp !== "NO" && <label className="flex min-h-8 items-center gap-2"><input type="checkbox" className="h-4 w-4 accent-[var(--primary)]" checked={f.acceptWhatsapp} onChange={(e) => setF({ ...f, acceptWhatsapp: e.target.checked })} />WhatsApp</label>}
          {f.email && <label className="flex min-h-8 items-center gap-2"><input type="checkbox" className="h-4 w-4 accent-[var(--primary)]" checked={f.acceptEmail} onChange={(e) => setF({ ...f, acceptEmail: e.target.checked })} />E-mail</label>}
          <p className="text-xs text-muted-foreground">{t("acceptsHint")}</p>
        </fieldset>
      )}
      {msg && <Alert tone={msg.tone} title={msg.text} />}
      <Button className="w-full" loading={busy} disabled={!f.phone.trim() && !f.email.trim()} onClick={save}>{t("save")}</Button>
    </Card>
  );
}

function List({ stage, wa, q, page, setPage, showStage }: { stage: string; wa: string; q: string; page: number; setPage: (n: number) => void; showStage: boolean }) {
  const t = useTranslations("journey.customers");
  const tc = useTranslations("common");
  const locale = useLocale();
  const p = new URLSearchParams({ sort: "lastPurchase", dir: "desc", page: String(page), size: "50" });
  if (stage) p.set("stage", stage);
  if (wa) p.set("whatsapp", wa);
  if (q) p.set("q", q);
  const query = useQuery({ queryKey: ["crm", "customers", p.toString()], queryFn: () => api<CrmPage>(`/crm/customers?${p}`) });
  if (query.isLoading) return <Spinner />;
  if (query.error) return <Alert tone="danger" title={errorMessage(query.error)} />;
  const d = query.data!;
  if (d.items.length === 0) return <EmptyState title={t("empty")} description={t("emptyText")} />;
  const pages = Math.max(1, Math.ceil(d.total / d.size));
  return (
    <>
      <div className="overflow-x-auto rounded-xl border border-border bg-card">
        <table className="w-full min-w-[640px] text-sm">
          <thead className="bg-muted/60 text-left text-xs text-muted-foreground">
            <tr>
              <th scope="col" className="px-3 py-2.5 font-medium">{t("col.customer")}</th>
              <th scope="col" className="px-3 py-2.5 font-medium">WhatsApp</th>
              {showStage && <th scope="col" className="px-3 py-2.5 font-medium">{t("col.stage")}</th>}
              <th scope="col" className="px-3 py-2.5 font-medium">{t("col.lastPurchase")}</th>
              <th scope="col" className="px-3 py-2.5 text-right font-medium">{t("col.total")}</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {d.items.map((c) => (
              <tr key={c.id} className="hover:bg-muted/40">
                <td className="px-3 py-2.5">
                  <Link href={`/app/contacts/${c.id}`} className="inline-flex items-center gap-1 font-medium hover:text-primary">
                    {c.name || c.email || c.phone || "—"}{c.vip && <Star className="h-3.5 w-3.5 fill-[var(--brand-orange-light)] text-[var(--brand-orange-light)]" aria-label="VIP" />}
                  </Link>
                  <div className="text-xs text-muted-foreground">{[c.phone, c.email].filter(Boolean).join(" · ")}</div>
                </td>
                <td className="px-3 py-2.5"><Badge tone={c.whatsappStatus === "YES" ? "success" : "neutral"}>{t(`wa.${c.whatsappStatus}`)}</Badge></td>
                {showStage && <td className="whitespace-nowrap px-3 py-2.5"><Badge tone={STAGE_TONE[c.stage]} className="whitespace-nowrap">{t(`stages.${c.stage}`)}</Badge></td>}
                <td className="whitespace-nowrap px-3 py-2.5">{c.lastPurchaseAt ? date(c.lastPurchaseAt, locale) : "—"}</td>
                <td className="whitespace-nowrap px-3 py-2.5 text-right tabular-nums">{c.orders > 0 ? `${money(c.totalSpentMinor, c.currency, locale)} · ${c.orders}×` : "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="flex items-center justify-between text-sm text-muted-foreground">
        <span>{t("total", { n: d.total.toLocaleString(locale) })}</span>
        <div className="flex items-center gap-2">
          <span className="tabular-nums">{page + 1} / {pages}</span>
          <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage(page - 1)}>{tc("back")}</Button>
          <Button variant="outline" size="sm" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>{tc("next")}</Button>
        </div>
      </div>
      <p className="text-xs text-muted-foreground">{t("lastPurchaseHint")}</p>
    </>
  );
}

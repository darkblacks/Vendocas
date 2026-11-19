"use client";
import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useLocale, useTranslations } from "next-intl";
import { api, errorMessage } from "@/lib/api";
import { dateTime, money, toMinor } from "@/lib/format";
import { Alert, Button, Card, CardTitle, Dialog, DialogContent, DialogTrigger, EmptyState, Field, Input, PageHeader, Select, Spinner, StatusBadge, Table, Td, Textarea } from "@/components/ui";

interface Campaign { id: string; name: string; status: string; pauseReason?: string; channel: string; maxRecipientsPerRun: number; budgetPerRunMinor: number;
  monthlyBudgetMinor: number; nextRunAt?: string; schedule?: { kind: string; localTime: string; timeZone: string } }
interface Estimate { eligible: number; perRun: number; unitCostMinor: number; estimatedRunCostMinor: number; currency: string; priced: boolean; walletAvailableMinor: number; limitingFactor?: string }
interface Run { id: string; scheduledFor: string; status: string; blockedReason?: string; queued: number; skipped: number; delivered: number; failed: number; replied: number; estimatedCostMinor: number }

export default function CampaignsPage() {
  const t = useTranslations("campaigns");
  const tc = useTranslations("common");
  const locale = useLocale();
  const ent = useQuery({ queryKey: ["entitlements"], queryFn: () => api<{ features: string[] }>("/billing/entitlements") });
  const list = useQuery({ queryKey: ["campaigns"], queryFn: () => api<Campaign[]>("/campaigns") });
  const [selected, setSelected] = useState<string | null>(null);
  const allowed = ent.data?.features.includes("CAMPAIGNS_DAILY");
  return (
    <div>
      <PageHeader title={t("title")} description={t("subtitle")} actions={allowed && <CampaignForm />} />
      {ent.data && !allowed && <Alert tone="warning" title={t("planRequired")} />}
      {list.isLoading && <Spinner />}
      {list.data?.length === 0 && allowed && <EmptyState title={t("new")} description={t("subtitle")} />}
      {!!list.data?.length && (
        <Table head={[tc("name"), tc("channel"), tc("status"), t("schedule"), t("budgetPerRun"), "Next", ""]}>
          {list.data.map((c) => (
            <tr key={c.id}><Td className="font-medium">{c.name}</Td><Td>{c.channel}</Td><Td><StatusBadge status={c.status} />{c.pauseReason && <p className="text-xs text-muted-foreground">{c.pauseReason}</p>}</Td>
              <Td className="text-xs">{c.schedule ? `${c.schedule.kind} ${c.schedule.localTime} (${c.schedule.timeZone})` : "—"}</Td>
              <Td>{c.budgetPerRunMinor}</Td><Td className="text-xs">{dateTime(c.nextRunAt, locale)}</Td>
              <Td className="space-x-1 whitespace-nowrap">
                <Button size="sm" variant="outline" onClick={() => setSelected(c.id)}>{tc("details")}</Button>
                {["DRAFT", "PAUSED"].includes(c.status)
                  ? <Button size="sm" onClick={async () => { try { await api(`/campaigns/${c.id}/activate`, { method: "POST" }); } catch (e) { alert(errorMessage(e)); } await list.refetch(); }}>{tc("activate")}</Button>
                  : <Button size="sm" variant="secondary" onClick={async () => { await api(`/campaigns/${c.id}/pause`, { method: "POST" }); await list.refetch(); }}>{tc("pause")}</Button>}
              </Td></tr>
          ))}
        </Table>
      )}
      {selected && <CampaignDetail id={selected} />}
    </div>
  );
}

function CampaignDetail({ id }: { id: string }) {
  const t = useTranslations("campaigns");
  const locale = useLocale();
  const est = useQuery({ queryKey: ["campaign-est", id], queryFn: () => api<Estimate>(`/campaigns/${id}/estimate`) });
  const runs = useQuery({ queryKey: ["campaign-runs", id], queryFn: () => api<Run[]>(`/campaigns/${id}/runs`) });
  return (
    <div className="mt-6 grid gap-4 lg:grid-cols-3">
      <Card>
        <CardTitle>{t("estimate")}</CardTitle>
        {est.data && (
          <dl className="mt-3 space-y-1 text-sm">
            <p><strong>{est.data.eligible}</strong> {t("eligible")}</p>
            <p><strong>{est.data.perRun}</strong> {t("perRun")}</p>
            <p>{est.data.priced ? money(est.data.estimatedRunCostMinor, est.data.currency, locale) : "Usage rate not configured — sending blocked"}</p>
            <p className="text-muted-foreground">Wallet: {money(est.data.walletAvailableMinor, est.data.currency, locale)}</p>
            {est.data.limitingFactor && <Alert tone="warning" title={est.data.limitingFactor} />}
          </dl>
        )}
      </Card>
      <Card className="lg:col-span-2">
        <CardTitle>{t("runs")}</CardTitle>
        <div className="mt-3">
          <Table head={["When", "Status", "Queued", "Skipped", "Delivered", "Failed", "Replies", "Cost"]}>
            {runs.data?.map((r) => (
              <tr key={r.id}><Td className="text-xs">{dateTime(r.scheduledFor, locale)}</Td><Td><StatusBadge status={r.status} />{r.blockedReason && <span className="text-xs"> {r.blockedReason}</span>}</Td>
                <Td>{r.queued}</Td><Td>{r.skipped}</Td><Td>{r.delivered}</Td><Td>{r.failed}</Td><Td>{r.replied}</Td><Td>{r.estimatedCostMinor}</Td></tr>
            ))}
          </Table>
        </div>
      </Card>
    </div>
  );
}

function CampaignForm() {
  const t = useTranslations("campaigns");
  const tc = useTranslations("common");
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  const [f, setF] = useState({ name: "", channel: "EMAIL", subject: "", body: "", linkUrl: "", templateId: "", kind: "DAILY", days: "1,2,3,4,5", time: "11:00",
    timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone, startDate: new Date().toISOString().slice(0, 10), endDate: "", maxRecipients: 500,
    budgetPerRun: "20.00", monthlyBudget: "300.00", frequencyCapDays: 1, tags: "" });
  const [media, setMedia] = useState<{ id: string; url?: string } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const templates = useQuery({ queryKey: ["templates", f.channel], enabled: open, queryFn: () => api<{ id: string; name: string; status: string; purpose: string }[]>(`/templates?channel=${f.channel}`) });

  async function upload(file: File) {
    const fd = new FormData(); fd.append("file", file); fd.append("purpose", "CAMPAIGN");
    try { setMedia(await api<{ id: string; url?: string }>("/media", { form: fd })); } catch (e) { setError(errorMessage(e)); }
  }
  async function save() {
    setError(null);
    try {
      await api("/campaigns", { body: {
        name: f.name, channel: f.channel, templateId: f.templateId || null, subject: f.subject || null, body: f.body || null, mediaAssetId: media?.id ?? null,
        linkUrl: f.linkUrl || null, audience: { tagsAny: f.tags.split(",").map((s) => s.trim()).filter(Boolean) },
        maxRecipientsPerRun: f.maxRecipients, budgetPerRunMinor: toMinor(f.budgetPerRun), monthlyBudgetMinor: toMinor(f.monthlyBudget), frequencyCapDays: f.frequencyCapDays,
        schedule: { kind: f.kind, daysOfWeek: f.days.split(",").map(Number).filter(Boolean), localTime: f.time, timeZone: f.timeZone, startDate: f.startDate, endDate: f.endDate || null },
      } });
      await qc.invalidateQueries({ queryKey: ["campaigns"] });
      setOpen(false);
    } catch (e) { setError(errorMessage(e)); }
  }
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild><Button>{t("new")}</Button></DialogTrigger>
      <DialogContent title={t("new")} className="max-w-2xl">
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label={tc("name")} htmlFor="cn"><Input id="cn" value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} /></Field>
          <Field label={tc("channel")} htmlFor="cch"><Select id="cch" value={f.channel} onChange={(e) => setF({ ...f, channel: e.target.value, templateId: "" })}><option>EMAIL</option><option>WHATSAPP</option><option>SMS</option></Select></Field>
          <div className="sm:col-span-2"><Field label="Template" htmlFor="ctp" hint={f.channel === "WHATSAPP" ? t("whatsappTemplate") : undefined}>
            <Select id="ctp" value={f.templateId} onChange={(e) => setF({ ...f, templateId: e.target.value })}><option value="">—</option>
              {templates.data?.filter((x) => x.purpose === "MARKETING").map((x) => <option key={x.id} value={x.id}>{x.name} ({x.status.toLowerCase()})</option>)}</Select></Field></div>
          {!f.templateId && f.channel === "EMAIL" && <div className="sm:col-span-2"><Field label="Subject" htmlFor="csj"><Input id="csj" value={f.subject} onChange={(e) => setF({ ...f, subject: e.target.value })} /></Field></div>}
          {!f.templateId && <div className="sm:col-span-2"><Field label="Message" htmlFor="cbd"><Textarea id="cbd" value={f.body} onChange={(e) => setF({ ...f, body: e.target.value })} /></Field></div>}
          <Field label={t("image")} htmlFor="cimg"><input id="cimg" type="file" accept="image/jpeg,image/png,image/webp" className="text-sm" onChange={(e) => e.target.files?.[0] && upload(e.target.files[0])} />
            {media?.url && <img src={media.url} alt="" className="mt-2 h-24 rounded object-cover" />}</Field>
          <Field label={t("link")} htmlFor="clk"><Input id="clk" type="url" value={f.linkUrl} onChange={(e) => setF({ ...f, linkUrl: e.target.value })} /></Field>
          <Field label={t("schedule")} htmlFor="ck"><Select id="ck" value={f.kind} onChange={(e) => setF({ ...f, kind: e.target.value })}><option value="DAILY">{t("daily")}</option><option value="WEEKLY">{t("weekly")}</option><option value="ONCE">{t("once")}</option></Select></Field>
          {f.kind === "WEEKLY" && <Field label="1=Mon … 7=Sun" htmlFor="cd"><Input id="cd" value={f.days} onChange={(e) => setF({ ...f, days: e.target.value })} /></Field>}
          <Field label={t("time")} htmlFor="ct"><Input id="ct" type="time" value={f.time} onChange={(e) => setF({ ...f, time: e.target.value })} /></Field>
          <Field label="Time zone" htmlFor="ctz"><Input id="ctz" value={f.timeZone} onChange={(e) => setF({ ...f, timeZone: e.target.value })} /></Field>
          <Field label={t("startDate")} htmlFor="csd"><Input id="csd" type="date" value={f.startDate} onChange={(e) => setF({ ...f, startDate: e.target.value })} /></Field>
          <Field label={t("endDate")} htmlFor="ced"><Input id="ced" type="date" value={f.endDate} onChange={(e) => setF({ ...f, endDate: e.target.value })} /></Field>
          <Field label={t("maxRecipients")} htmlFor="cmr"><Input id="cmr" type="number" min={1} value={f.maxRecipients} onChange={(e) => setF({ ...f, maxRecipients: Number(e.target.value) })} /></Field>
          <Field label={t("frequencyCap")} htmlFor="cfc"><Input id="cfc" type="number" min={1} value={f.frequencyCapDays} onChange={(e) => setF({ ...f, frequencyCapDays: Number(e.target.value) })} /></Field>
          <Field label={t("budgetPerRun")} htmlFor="cbr"><Input id="cbr" inputMode="decimal" value={f.budgetPerRun} onChange={(e) => setF({ ...f, budgetPerRun: e.target.value })} /></Field>
          <Field label={t("monthlyBudget")} htmlFor="cmb"><Input id="cmb" inputMode="decimal" value={f.monthlyBudget} onChange={(e) => setF({ ...f, monthlyBudget: e.target.value })} /></Field>
          <div className="sm:col-span-2"><Field label="Audience tags" htmlFor="ctg" hint="Only customers with MARKETING authorization on this channel are eligible."><Input id="ctg" value={f.tags} onChange={(e) => setF({ ...f, tags: e.target.value })} /></Field></div>
          {error && <div className="sm:col-span-2"><Alert tone="danger" title={error} /></div>}
          <div className="sm:col-span-2"><Button onClick={save}>{tc("save")}</Button></div>
        </div>
      </DialogContent>
    </Dialog>
  );
}

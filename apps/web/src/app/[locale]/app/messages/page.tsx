"use client";
import { useEffect, useMemo, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { Megaphone, PhoneMissed, UserRoundX } from "lucide-react";
import { api, errorMessage } from "@/lib/api";
import { useAuth, useCan } from "@/lib/auth";
import { Link } from "@/i18n/navigation";
import { Alert, Button, Card, Field, Input, PageHeader, Select, Spinner, Switch, Textarea, cn } from "@/components/ui";
import { PlanBadge, useEntitlements } from "@/features/plan";
import { AlertFooterPreview } from "@/features/alert-footer";
import { PRESET_INACTIVE, PRESET_MISSED_CALL, configFrom, definitionFor, type Channel, type PresetConfig, type PresetKey } from "@/features/messages-presets";

interface Automation { id: string; name: string; description?: string | null; status: string; running: number; completed: number; blocked: number }
interface Detail { summary: Automation; draft?: unknown; active?: unknown; validationErrors: string[] }
interface Reach { storeName?: string | null; alertContact?: string | null; alertEmail?: string | null }

/** Minhas mensagens: the two ready messages (7 days without buying, missed-call return) and campaigns (Ilimitado). */
export default function MessagesPage() {
  const t = useTranslations("journey.messages");
  const ent = useEntitlements();
  const list = useQuery({ queryKey: ["automations"], queryFn: () => api<Automation[]>("/automations") });
  const [selected, setSelected] = useState<PresetKey>(PRESET_INACTIVE);
  const f = ent.data?.features ?? [];
  const campaigns = !!ent.data && (!f.includes("ALERTS") || f.includes("CAMPAIGNS"));
  const byKey = (k: PresetKey) => list.data?.find((a) => a.description === k);

  const presets: { key: PresetKey; icon: typeof UserRoundX; title: string; when: string }[] = [
    { key: PRESET_INACTIVE, icon: UserRoundX, title: t("inactive.title"), when: t("inactive.when") },
    { key: PRESET_MISSED_CALL, icon: PhoneMissed, title: t("missed.title"), when: t("missed.when") },
  ];

  return (
    <div className="mx-auto max-w-6xl">
      <PageHeader title={t("title")} description={t("subtitle")} />
      {list.isLoading && <Spinner />}
      {list.data && (
        <div className="grid items-start gap-5 lg:grid-cols-[minmax(0,1fr)_minmax(0,28rem)]">
          <div className="space-y-3">
            {presets.map(({ key, icon: Icon, title, when }) => {
              const a = byKey(key);
              const on = a?.status === "ACTIVE";
              return (
                <div key={key} className={cn("flex items-start gap-4 rounded-2xl border bg-card p-5", selected === key ? "border-primary" : "border-border")}>
                  <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-accent text-accent-foreground"><Icon className="h-5 w-5" aria-hidden /></span>
                  <div className="min-w-0 flex-1">
                    <h2 className="font-semibold">{title}</h2>
                    <p className="text-sm text-muted-foreground">{when}</p>
                    <p className="mt-1 text-xs text-muted-foreground">{on ? t("statusOn", { sent: a!.completed + a!.running }) : a ? t("statusOff") : t("statusNew")}</p>
                    <button type="button" onClick={() => setSelected(key)} className="mt-2 text-sm font-medium text-primary underline-offset-4 hover:underline">{t("edit")}</button>
                  </div>
                </div>
              );
            })}
            {campaigns
              ? <Link href="/app/campaigns" className="flex items-start gap-4 rounded-2xl border border-border bg-card p-5 hover:border-primary">
                  <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-accent text-accent-foreground"><Megaphone className="h-5 w-5" aria-hidden /></span>
                  <div><h2 className="font-semibold">{t("campaigns.title")}</h2><p className="text-sm text-muted-foreground">{t("campaigns.text")}</p></div>
                </Link>
              : <Link href="/app/billing" className="flex items-start gap-4 rounded-2xl border border-dashed border-border bg-card/60 p-5">
                  <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-muted"><Megaphone className="h-5 w-5" aria-hidden /></span>
                  <div><PlanBadge feature="CAMPAIGNS" /><h2 className="mt-2 font-semibold">{t("campaigns.title")}</h2><p className="text-sm text-muted-foreground">{t("campaigns.text")}</p></div>
                </Link>}
            <p className="text-xs text-muted-foreground">{t("advanced")} <Link href="/app/automations" className="text-primary">{t("advancedLink")}</Link></p>
          </div>
          <Editor key={selected + (byKey(selected)?.id ?? "new")} preset={selected} automation={byKey(selected)} alertsOnly={f.includes("ALERTS_ONLY")} />
        </div>
      )}
    </div>
  );
}

function Editor({ preset, automation, alertsOnly }: { preset: PresetKey; automation?: Automation; alertsOnly: boolean }) {
  const t = useTranslations("journey.messages");
  const qc = useQueryClient();
  const canWrite = useCan("operate");
  const inactive = preset === PRESET_INACTIVE;
  const defaults: PresetConfig = useMemo(() => inactive
    ? { days: 7, channel: "EMAIL", subject: t.raw("inactive.subject") as string, body: t.raw("inactive.body") as string }
    : { days: 0, channel: "SMS", subject: "", body: t.raw("missed.body") as string }, [inactive, t]);
  const detail = useQuery({ queryKey: ["automation", automation?.id], enabled: !!automation, queryFn: () => api<Detail>(`/automations/${automation!.id}`) });
  const { orgId } = useAuth();
  const reach = useQuery({ queryKey: ["onboarding-state", orgId], enabled: !!orgId, queryFn: () => api<Reach>("/onboarding") });
  const [c, setC] = useState<PresetConfig>(defaults);
  const [on, setOn] = useState(automation?.status === "ACTIVE");
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ tone: "success" | "danger"; text: string } | null>(null);
  useEffect(() => {
    if (!detail.data) return;
    const cfg = configFrom(detail.data.draft ?? detail.data.active, defaults);
    setC({ ...cfg, subject: toFriendly(cfg.subject), body: toFriendly(cfg.body) });
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [detail.data, defaults]);

  // The store edits friendly tokens ([nome], [loja]); the engine uses {{contact.first_name}} / {{business.name}}.
  const nameTok = t("tokenName"), storeTok = t("tokenStore");
  const toFriendly = (x: string) => x.replaceAll("{{contact.first_name}}", nameTok).replaceAll("{{business.name}}", storeTok);
  const toEngine = (x: string) => x.replaceAll(nameTok, "{{contact.first_name}}").replaceAll(storeTok, "{{business.name}}");
  const preview = c.body.replaceAll(nameTok, "Maria").replaceAll(storeTok, t("previewStore"));
  async function save() {
    setBusy(true); setMsg(null);
    try {
      const body = { name: inactive ? t("inactive.title") : t("missed.title"), description: preset, editorMode: "SIMPLE", definition: definitionFor(preset, { ...c, subject: toEngine(c.subject), body: toEngine(c.body) }) };
      let id = automation?.id;
      if (id) await api(`/automations/${id}`, { method: "PUT", body });
      else id = (await api<{ id: string }>("/automations", { body })).id;
      if (on) await api(`/automations/${id}/activate`, { method: "POST", body: { migrationPolicy: "KEEP_ON_OLD" } });
      else if (automation?.status === "ACTIVE") await api(`/automations/${id}/pause`, { method: "POST" });
      await qc.invalidateQueries({ queryKey: ["automations"] });
      setMsg({ tone: "success", text: on ? t("savedOn") : t("savedOff") });
    } catch (e) { setMsg({ tone: "danger", text: errorMessage(e) }); } finally { setBusy(false); }
  }

  return (
    <Card className="space-y-4 lg:sticky lg:top-4">
      <div className="flex items-center justify-between gap-3">
        <h2 className="text-lg font-semibold">{inactive ? t("inactive.title") : t("missed.title")}</h2>
        <label className="flex items-center gap-2 text-sm"><Switch checked={on} onCheckedChange={setOn} disabled={!canWrite} aria-label={t("toggle")} />{on ? t("on") : t("off")}</label>
      </div>
      {inactive && (
        <Field label={t("inactive.days")} htmlFor="m-days" hint={t("inactive.daysHint")}>
          <Input id="m-days" type="number" min={1} max={365} value={c.days} onChange={(e) => setC({ ...c, days: Math.max(1, Number(e.target.value) || 1) })} />
        </Field>
      )}
      {!inactive && <p className="rounded-lg bg-muted p-3 text-sm text-muted-foreground">{t("missed.requires")} <Link href="/app/channels" className="text-primary">{t("missed.setup")}</Link></p>}
      <Field label={t("channel")} htmlFor="m-ch">
        <Select id="m-ch" value={c.channel} onChange={(e) => setC({ ...c, channel: e.target.value as Channel })}>
          <option value="EMAIL">{t("channels.EMAIL")}</option>
          <option value="SMS">{t("channels.SMS")}</option>
          <option value="WHATSAPP">{t("channels.WHATSAPP")}</option>
        </Select>
      </Field>
      {c.channel === "WHATSAPP" && <p className="text-xs text-muted-foreground">{t("whatsappTemplate")}</p>}
      {c.channel === "EMAIL" && <Field label={t("subject")} htmlFor="m-subj"><Input id="m-subj" value={c.subject} onChange={(e) => setC({ ...c, subject: e.target.value })} /></Field>}
      <Field label={t("message")} htmlFor="m-body" hint={t("variables", { name: nameTok, store: storeTok })}>
        <Textarea id="m-body" rows={4} value={c.body} onChange={(e) => setC({ ...c, body: e.target.value })} />
      </Field>
      <div className="rounded-xl bg-muted p-4">
        <p className="mb-2 text-xs text-muted-foreground">{t("preview")}</p>
        <div className="rounded-xl rounded-bl-sm border border-border bg-card p-3 text-sm leading-relaxed">
          {preview}
          {alertsOnly && <AlertFooterPreview className="mt-2 border-t border-dashed border-border pt-2 text-muted-foreground"
            store={reach.data?.storeName} email={reach.data?.alertEmail} phone={reach.data?.alertContact} />}
        </div>
      </div>
      {msg && <Alert tone={msg.tone} title={msg.text} />}
      <Button className="w-full" loading={busy} disabled={!canWrite || !c.body.trim()} onClick={save}>{t("save")}</Button>
    </Card>
  );
}

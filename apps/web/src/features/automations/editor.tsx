"use client";
import { useEffect, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useLocale, useTranslations } from "next-intl";
import { ArrowDown, Trash2 } from "lucide-react";
import { useRouter } from "@/i18n/navigation";
import { api, errorMessage } from "@/lib/api";
import { dateTime, money } from "@/lib/format";
import { useCan } from "@/lib/auth";
import type { Contact, Page } from "@/features/types";
import { Alert, Badge, Button, Card, CardTitle, Field, Input, PageHeader, Select, StatusBadge, Table, Tabs, TabsContent, TabsList, TabsTrigger, Td } from "@/components/ui";
import { EXIT_EVENTS, STEP_TYPES, TRIGGERS, UNITS, defaultDefinition, linearize, newStep, type Definition } from "./model";
import { StepEditor } from "./step-editor";
import { FlowEditor } from "./flow-editor";

interface Detail {
  summary: { id: string; name: string; description?: string; status: string; editorMode: string; activeVersion?: number; draftVersion?: number; running: number; completed: number; blocked: number };
  draft?: Definition; active?: Definition; validationErrors: string[];
}
interface SimStep { stepId: string; type: string; at: string; outcome: string; detail?: string; previewSubject?: string; previewBody?: string }
interface Estimate { audience: number; channels: { stepId: string; channel: string; reachable: number; totalCostMinor: number; currency: string; priced: boolean }[]; note: string }

export function AutomationEditor({ id }: { id?: string }) {
  const t = useTranslations("automations");
  const tc = useTranslations("common");
  const locale = useLocale();
  const router = useRouter();
  const qc = useQueryClient();
  const canActivate = useCan("manage");
  const detail = useQuery({ queryKey: ["automation", id], enabled: !!id, queryFn: () => api<Detail>(`/automations/${id}`) });
  const [name, setName] = useState("Reactivation — 3 months");
  const [mode, setMode] = useState<"SIMPLE" | "ADVANCED">("SIMPLE");
  const [def, setDef] = useState<Definition>(defaultDefinition());
  const [msg, setMsg] = useState<{ tone: "success" | "danger" | "warning"; text: string; list?: string[] } | null>(null);
  const [sim, setSim] = useState<SimStep[] | null>(null);
  const [est, setEst] = useState<Estimate | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [migration, setMigration] = useState("KEEP_ON_OLD");

  useEffect(() => {
    if (detail.data) {
      setName(detail.data.summary.name);
      setMode(detail.data.summary.editorMode === "ADVANCED" ? "ADVANCED" : "SIMPLE");
      const d = detail.data.draft ?? detail.data.active;
      if (d) setDef(d);
    }
  }, [detail.data]);

  const payload = () => ({ name, editorMode: mode, definition: mode === "SIMPLE" ? { ...def, steps: linearize(def.steps) } : def });

  async function run(label: string, fn: () => Promise<void>) {
    setBusy(label); setMsg(null);
    try { await fn(); } catch (e) {
      const errs = (e as { errors?: string[] }).errors;
      setMsg({ tone: "danger", text: errorMessage(e), list: errs?.length ? errs : undefined });
    } finally { setBusy(null); }
  }

  const save = () => run("save", async () => {
    if (id) { await api(`/automations/${id}`, { method: "PUT", body: payload() }); await qc.invalidateQueries({ queryKey: ["automation", id] }); setMsg({ tone: "success", text: tc("saved") }); }
    else { const r = await api<{ id: string }>("/automations", { body: payload() }); router.replace(`/app/automations/${r.id}`); }
  });

  const trig = def.trigger;
  const setTrig = (p: Partial<Definition["trigger"]>) => setDef({ ...def, trigger: { ...trig, ...p } });

  return (
    <div className="space-y-4">
      <PageHeader title={id ? name : t("new")} description={t("subtitle")}
        actions={<>
          {detail.data && <StatusBadge status={detail.data.summary.status} />}
          <Button variant="outline" loading={busy === "save"} onClick={save}>{tc("save")}</Button>
          {id && canActivate && detail.data?.summary.status === "ACTIVE" && (
            <Button variant="secondary" onClick={() => run("pause", async () => { await api(`/automations/${id}/pause`, { method: "POST" }); await detail.refetch(); })}>{tc("pause")}</Button>
          )}
          {id && canActivate && (
            <Button loading={busy === "activate"} onClick={() => run("activate", async () => {
              await api(`/automations/${id}`, { method: "PUT", body: payload() });
              await api(`/automations/${id}/activate`, { body: { migrationPolicy: migration } });
              await detail.refetch();
              setMsg({ tone: "success", text: "ACTIVE" });
            })}>{tc("activate")}</Button>
          )}
        </>} />
      {msg && <Alert tone={msg.tone} title={msg.text}>{msg.list && <ul className="list-disc pl-5">{msg.list.map((x) => <li key={x}>{x}</li>)}</ul>}</Alert>}
      {detail.data?.summary.activeVersion && (
        <Card className="flex flex-wrap items-center gap-4 text-sm">
          <span>v{detail.data.summary.activeVersion} active{detail.data.summary.draftVersion ? ` · draft v${detail.data.summary.draftVersion}` : ""}</span>
          <span>{detail.data.summary.running} {t("running")} · {detail.data.summary.completed} {t("completed")} · {detail.data.summary.blocked} {t("skipped")}</span>
          <label className="flex items-center gap-2">{t("migration")}:
            <Select className="h-8 w-64" value={migration} onChange={(e) => setMigration(e.target.value)}><option value="KEEP_ON_OLD">{t("keepOld")}</option><option value="CANCEL_RUNNING">{t("cancelOld")}</option></Select>
          </label>
        </Card>
      )}

      <Card className="grid gap-3 sm:grid-cols-2">
        <Field label={tc("name")} htmlFor="aname"><Input id="aname" value={name} onChange={(e) => setName(e.target.value)} /></Field>
        <Field label={t("trigger")} htmlFor="atrig">
          <Select id="atrig" value={trig.type} onChange={(e) => setTrig({ type: e.target.value })}>{TRIGGERS.map((x) => <option key={x} value={x}>{t(`triggers.${x}`)}</option>)}</Select>
        </Field>
        {trig.type === "CONTACT_INACTIVE" && (<>
          <Field label="Period" htmlFor="per"><div className="grid grid-cols-2 gap-2">
            <Input id="per" type="number" min={1} value={trig.period?.amount ?? 3} onChange={(e) => setTrig({ period: { amount: Number(e.target.value), unit: trig.period?.unit ?? "CALENDAR_MONTHS" } })} />
            <Select aria-label="unit" value={trig.period?.unit ?? "CALENDAR_MONTHS"} onChange={(e) => setTrig({ period: { amount: trig.period?.amount ?? 3, unit: e.target.value as never } })}>{UNITS.map((u) => <option key={u} value={u}>{t(`units.${u}`)}</option>)}</Select>
          </div></Field>
          <Field label="Since" htmlFor="basis"><Select id="basis" value={trig.basis ?? "LAST_PURCHASE"} onChange={(e) => setTrig({ basis: e.target.value })}>
            <option value="LAST_PURCHASE">last purchase</option><option value="LAST_INTERACTION">last interaction</option><option value="LAST_VISIT">last visit</option></Select></Field>
        </>)}
        {trig.type === "APPOINTMENT_UPCOMING" && <Field label="Minutes before" htmlFor="lead"><Input id="lead" type="number" min={15} value={trig.leadMinutes ?? 1440} onChange={(e) => setTrig({ leadMinutes: Number(e.target.value) })} /></Field>}
        {trig.type === "EXTERNAL_EVENT" && <Field label="Event name" htmlFor="evt"><Input id="evt" value={trig.eventType ?? ""} onChange={(e) => setTrig({ eventType: e.target.value })} /></Field>}
        {trig.type === "DATE_FIELD" && <Field label="Custom field key" htmlFor="df"><Input id="df" value={trig.dateField ?? ""} onChange={(e) => setTrig({ dateField: e.target.value })} /></Field>}
        {trig.type === "RECURRING" && (
          <Field label="Schedule" htmlFor="rec"><div className="grid grid-cols-2 gap-2">
            <Select id="rec" value={trig.schedule?.recurrence ?? "DAILY"} onChange={(e) => setTrig({ schedule: { time: trig.schedule?.time ?? "09:00", ...trig.schedule, recurrence: e.target.value } })}><option>DAILY</option><option>WEEKLY</option><option>MONTHLY</option></Select>
            <Input type="time" aria-label="time" value={trig.schedule?.time ?? "09:00"} onChange={(e) => setTrig({ schedule: { recurrence: trig.schedule?.recurrence ?? "DAILY", ...trig.schedule, time: e.target.value } })} />
            {trig.schedule?.recurrence === "WEEKLY" && <Input aria-label="weekdays 1-7" placeholder="1,3,5" value={(trig.schedule.daysOfWeek ?? []).join(",")} onChange={(e) => setTrig({ schedule: { ...trig.schedule!, daysOfWeek: e.target.value.split(",").map(Number).filter((n) => n >= 1 && n <= 7) } })} />}
            {trig.schedule?.recurrence === "MONTHLY" && <Input type="number" min={1} max={31} aria-label="day of month" value={trig.schedule.dayOfMonth ?? 1} onChange={(e) => setTrig({ schedule: { ...trig.schedule!, dayOfMonth: Number(e.target.value) } })} />}
          </div></Field>
        )}
        <Field label="Audience tags (optional)" htmlFor="aud"><Input id="aud" placeholder="vip, newsletter" value={(trig.audience?.tagsAny ?? []).join(", ")}
          onChange={(e) => setTrig({ audience: { ...trig.audience, tagsAny: e.target.value.split(",").map((s) => s.trim()).filter(Boolean) } })} /></Field>
      </Card>

      <Tabs value={mode} onValueChange={(v) => setMode(v as "SIMPLE" | "ADVANCED")}>
        <TabsList><TabsTrigger value="SIMPLE">{t("simple")}</TabsTrigger><TabsTrigger value="ADVANCED">{t("advanced")}</TabsTrigger></TabsList>
        <TabsContent value="SIMPLE">
          <div className="space-y-2">
            {def.steps.map((s, i) => (
              <div key={s.id}>
                <Card className="p-4">
                  <div className="mb-3 flex items-center justify-between">
                    <Badge tone="info">{i + 1}. {s.type === "WAIT" ? t("wait") : s.type === "CONDITION" ? t("check") : s.type === "SEND" ? t("send") : s.type.replaceAll("_", " ")}</Badge>
                    <Button variant="ghost" size="icon" aria-label={tc("delete")} onClick={() => setDef({ ...def, steps: def.steps.filter((x) => x.id !== s.id) })}><Trash2 className="h-4 w-4" /></Button>
                  </div>
                  <StepEditor step={s} onChange={(ns) => setDef({ ...def, steps: def.steps.map((x) => (x.id === ns.id ? ns : x)) })} />
                </Card>
                {i < def.steps.length - 1 && <ArrowDown className="mx-auto my-1 h-4 w-4 text-muted-foreground" aria-hidden />}
              </div>
            ))}
            <div className="flex flex-wrap gap-2">
              {STEP_TYPES.filter((x) => x !== "END").map((x) => <Button key={x} size="sm" variant="outline" onClick={() => setDef({ ...def, steps: [...def.steps, newStep(x)] })}>+ {x.replaceAll("_", " ").toLowerCase()}</Button>)}
            </div>
          </div>
        </TabsContent>
        <TabsContent value="ADVANCED"><FlowEditor def={def} onChange={setDef} /></TabsContent>
      </Tabs>

      <Card className="grid gap-3 sm:grid-cols-3">
        <Field label={t("exitOn")} htmlFor="exit"><div id="exit" className="flex flex-col gap-1 text-sm">{EXIT_EVENTS.map((ev) => (
          <label key={ev} className="flex items-center gap-2"><input type="checkbox" checked={def.settings?.exitOn?.includes(ev) ?? false}
            onChange={(e) => setDef({ ...def, settings: { ...def.settings, exitOn: e.target.checked ? [...(def.settings?.exitOn ?? []), ev] : (def.settings?.exitOn ?? []).filter((x) => x !== ev) } })} />{ev}</label>))}</div></Field>
        <Field label={t("budget")} htmlFor="bud" hint="minor units (e.g. 5000 = 50.00)"><Input id="bud" type="number" min={0} value={def.settings?.monthlyBudgetMinor ?? ""} onChange={(e) => setDef({ ...def, settings: { ...def.settings, monthlyBudgetMinor: e.target.value ? Number(e.target.value) : undefined } })} /></Field>
        <Field label={t("lateTolerance")} htmlFor="late"><Input id="late" type="number" min={1} value={def.settings?.lateToleranceHours ?? 24} onChange={(e) => setDef({ ...def, settings: { ...def.settings, lateToleranceHours: Number(e.target.value) } })} /></Field>
      </Card>

      <Card>
        <div className="flex flex-wrap gap-2">
          <Button variant="outline" loading={busy === "sim"} onClick={() => run("sim", async () => setSim(await api<SimStep[]>("/automations/simulate", { body: { definition: payload().definition } })))}>{t("simulate")}</Button>
          <Button variant="outline" loading={busy === "est"} onClick={() => run("est", async () => setEst(await api<Estimate>("/automations/estimate", { body: { definition: payload().definition } })))}>{t("estimate")}</Button>
          {id && <TestRun id={id} />}
        </div>
        <p className="mt-2 text-xs text-muted-foreground">{t("simulationNote")}</p>
        {sim && (
          <ol className="mt-4 space-y-2 text-sm">{sim.map((s, i) => (
            <li key={i} className="rounded-md border border-border p-2">
              <div className="flex flex-wrap items-center gap-2"><span className="text-xs text-muted-foreground">{dateTime(s.at, locale)}</span><Badge>{s.type}</Badge><StatusBadge status={s.outcome} /><span className="text-muted-foreground">{s.detail}</span></div>
              {(s.previewSubject || s.previewBody) && <div className="mt-2 rounded bg-muted p-2"><p className="font-medium">{s.previewSubject}</p><p className="whitespace-pre-wrap">{s.previewBody}</p></div>}
            </li>))}</ol>
        )}
        {est && (
          <div className="mt-4 text-sm">
            <p>Audience now: <strong>{est.audience}</strong></p>
            <ul className="mt-2 list-disc pl-5">{est.channels.map((c) => <li key={c.stepId}>{c.channel}: {c.reachable} reachable · {c.priced ? money(c.totalCostMinor, c.currency, locale) : "usage rate not configured — cannot send"}</li>)}</ul>
            <p className="mt-2 text-xs text-muted-foreground">{est.note}</p>
          </div>
        )}
      </Card>
      {id && <Executions id={id} />}
    </div>
  );
}

function TestRun({ id }: { id: string }) {
  const t = useTranslations("automations");
  const [contactId, setContactId] = useState("");
  const [result, setResult] = useState<string | null>(null);
  const testers = useQuery({ queryKey: ["testers"], queryFn: () => api<Page<Contact>>("/contacts?tag=test-recipient&size=50") });
  return (
    <div className="flex flex-wrap items-center gap-2">
      <Select className="h-10 w-56" aria-label="test recipient" value={contactId} onChange={(e) => setContactId(e.target.value)}>
        <option value="">test-recipient…</option>
        {testers.data?.items.map((c) => <option key={c.id} value={c.id}>{c.name ?? c.email ?? c.phoneE164}</option>)}
      </Select>
      <Button variant="secondary" disabled={!contactId} onClick={async () => {
        try { const r = await api<{ channel: string; status: string; reason?: string }[]>(`/automations/${id}/test-run`, { body: { contactId } }); setResult(r.map((x) => `${x.channel}: ${x.status}${x.reason ? ` (${x.reason})` : ""}`).join(" · ")); }
        catch (e) { setResult(errorMessage(e)); }
      }}>{t("testRun")}</Button>
      {result && <span className="text-sm">{result}</span>}
    </div>
  );
}

function Executions({ id }: { id: string }) {
  const t = useTranslations("automations");
  const locale = useLocale();
  const [open, setOpen] = useState<string | null>(null);
  const instances = useQuery({ queryKey: ["instances", id], queryFn: () => api<{ id: string; contactName?: string; state: string; currentStepId?: string; nextRunAt?: string; result?: string; blockedReason?: string; versionNo: number; startedAt: string }[]>(`/automations/${id}/instances`), refetchInterval: 15000 });
  const execs = useQuery({ queryKey: ["execs", open], enabled: !!open, queryFn: () => api<{ stepId: string; stepType: string; outcome: string; detail?: string; createdAt: string }[]>(`/automations/instances/${open}/executions`) });
  return (
    <Card>
      <CardTitle>{t("instances")}</CardTitle>
      <div className="mt-3">
        <Table head={["Customer", "State", "Step", "Next run", "Result", "v", ""]}>
          {instances.data?.map((i) => (
            <tr key={i.id}><Td>{i.contactName ?? "—"}</Td><Td><StatusBadge status={i.state} /></Td><Td className="font-mono text-xs">{i.currentStepId}</Td>
              <Td className="text-xs">{dateTime(i.nextRunAt, locale)}</Td><Td className="text-xs">{i.blockedReason ?? i.result}</Td><Td>{i.versionNo}</Td>
              <Td><Button size="sm" variant="ghost" onClick={() => setOpen(open === i.id ? null : i.id)}>log</Button></Td></tr>
          ))}
        </Table>
        {open && execs.data && (
          <ol className="mt-3 space-y-1 text-xs">{execs.data.map((e, k) => <li key={k}>{dateTime(e.createdAt, locale)} · {e.stepId} · {e.stepType} · <StatusBadge status={e.outcome} /> {e.detail}</li>)}</ol>
        )}
      </div>
    </Card>
  );
}

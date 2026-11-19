"use client";
import { useQuery } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { api } from "@/lib/api";
import { PURPOSES } from "@/features/types";
import { Button, Field, Input, Select, Textarea } from "@/components/ui";
import { CONDITIONS, UNITS, type Condition, type Step } from "./model";

export function StepEditor({ step, onChange }: { step: Step; onChange: (s: Step) => void }) {
  const t = useTranslations("automations");
  const tc = useTranslations("common");
  const templates = useQuery({
    queryKey: ["templates", step.channel], enabled: step.type === "SEND",
    queryFn: () => api<{ id: string; name: string; status: string; locale: string }[]>(`/templates?channel=${step.channel}`),
  });
  const set = (patch: Partial<Step>) => onChange({ ...step, ...patch });
  switch (step.type) {
    case "WAIT":
      return (
        <div className="grid grid-cols-2 gap-2">
          <Input type="number" min={1} aria-label="amount" value={step.duration?.amount ?? 1} onChange={(e) => set({ duration: { amount: Number(e.target.value), unit: step.duration?.unit ?? "DAYS" } })} />
          <Select aria-label="unit" value={step.duration?.unit} onChange={(e) => set({ duration: { amount: step.duration?.amount ?? 1, unit: e.target.value as never } })}>
            {UNITS.map((u) => <option key={u} value={u}>{t(`units.${u}`)}</option>)}
          </Select>
        </div>
      );
    case "WAIT_UNTIL":
      return (
        <div className="grid grid-cols-3 gap-2 text-sm">
          <Input type="time" aria-label="time" value={step.time ?? "10:00"} onChange={(e) => set({ time: e.target.value })} />
          <Select aria-label="zone" value={step.zone ?? "CONTACT"} onChange={(e) => set({ zone: e.target.value })}><option value="CONTACT">Customer time zone</option><option value="ORGANIZATION">Business time zone</option></Select>
          <label className="flex items-center gap-2"><input type="checkbox" checked={!!step.businessDaysOnly} onChange={(e) => set({ businessDaysOnly: e.target.checked })} />Mon–Fri</label>
        </div>
      );
    case "CONDITION":
      return (
        <div className="space-y-2">
          {(step.conditions ?? []).map((c, i) => (
            <ConditionRow key={i} c={c} onChange={(nc) => set({ conditions: step.conditions!.map((x, j) => (j === i ? nc : x)) })}
              onRemove={() => set({ conditions: step.conditions!.filter((_, j) => j !== i) })} />
          ))}
          <div className="flex gap-2">
            <Button size="sm" variant="outline" onClick={() => set({ conditions: [...(step.conditions ?? []), { type: "NO_PURCHASE_SINCE_TRIGGER" }] })}>+ condition</Button>
            <Select className="h-8 w-28" aria-label="match" value={step.match ?? "ALL"} onChange={(e) => set({ match: e.target.value })}><option value="ALL">ALL</option><option value="ANY">ANY</option></Select>
          </div>
        </div>
      );
    case "SEND":
      return (
        <div className="space-y-2">
          <div className="grid grid-cols-2 gap-2">
            <Field label={tc("channel")} htmlFor={`${step.id}ch`}><Select id={`${step.id}ch`} value={step.channel} onChange={(e) => set({ channel: e.target.value, templateId: undefined })}>
              {["EMAIL", "WHATSAPP", "SMS"].map((c) => <option key={c}>{c}</option>)}</Select></Field>
            <Field label={tc("purpose")} htmlFor={`${step.id}pu`}><Select id={`${step.id}pu`} value={step.purpose} onChange={(e) => set({ purpose: e.target.value })}>
              {PURPOSES.map((p) => <option key={p} value={p}>{t(`purposes.${p}`)}</option>)}</Select></Field>
          </div>
          <Field label="Template" htmlFor={`${step.id}tp`} hint={step.channel === "WHATSAPP" ? "WhatsApp business-initiated messages require an approved template." : undefined}>
            <Select id={`${step.id}tp`} value={step.templateId ?? ""} onChange={(e) => set({ templateId: e.target.value || undefined })}>
              <option value="">— {step.channel === "WHATSAPP" ? "select" : "write below"} —</option>
              {templates.data?.map((tp) => <option key={tp.id} value={tp.id}>{tp.name} ({tp.locale}, {tp.status.toLowerCase()})</option>)}
            </Select>
          </Field>
          {!step.templateId && (
            <>
              {step.channel === "EMAIL" && <Field label={t("subject")} htmlFor={`${step.id}sj`}><Input id={`${step.id}sj`} value={step.subject ?? ""} onChange={(e) => set({ subject: e.target.value })} /></Field>}
              <Field label={t("message")} htmlFor={`${step.id}bd`} hint={t("variables", { vars: "{{contact.first_name}} {{contact.name}} {{business.name}}" })}>
                <Textarea id={`${step.id}bd`} value={step.body ?? ""} onChange={(e) => set({ body: e.target.value })} />
              </Field>
            </>
          )}
        </div>
      );
    case "ADD_TAG": case "REMOVE_TAG":
      return <Input aria-label="tag" placeholder="tag" value={step.tag ?? ""} onChange={(e) => set({ tag: e.target.value })} />;
    case "CREATE_TASK":
      return <div className="grid grid-cols-2 gap-2"><Input aria-label="title" value={step.title ?? ""} onChange={(e) => set({ title: e.target.value })} /><Input type="number" aria-label="due in hours" value={step.dueInHours ?? 24} onChange={(e) => set({ dueInHours: Number(e.target.value) })} /></div>;
    case "CREATE_OPPORTUNITY":
      return <Input aria-label="title" value={step.title ?? ""} onChange={(e) => set({ title: e.target.value })} />;
    case "NOTIFY_OPERATOR":
      return <Textarea aria-label="message" value={step.message ?? ""} onChange={(e) => set({ message: e.target.value })} />;
    case "SET_CONTACT_STATUS":
      return <Select aria-label="status" value={step.status} onChange={(e) => set({ status: e.target.value })}><option>ACTIVE</option><option>BLOCKED</option></Select>;
    case "CALL_WEBHOOK":
      return <Input aria-label="url" type="url" value={step.url ?? ""} onChange={(e) => set({ url: e.target.value })} />;
    default:
      return null;
  }
}

function ConditionRow({ c, onChange, onRemove }: { c: Condition; onChange: (c: Condition) => void; onRemove: () => void }) {
  const t = useTranslations("automations");
  const needsChannel = ["CONSENT", "CHANNEL_CONNECTED", "CHANNEL_IN_PLAN", "NOT_MESSAGED_RECENTLY"].includes(c.type);
  return (
    <div className="flex flex-wrap items-center gap-2 rounded-md border border-border p-2 text-sm">
      <Select className="h-8 w-56" aria-label="condition" value={c.type} onChange={(e) => onChange({ type: e.target.value })}>{CONDITIONS.map((x) => <option key={x}>{x}</option>)}</Select>
      {needsChannel && <Select className="h-8 w-32" aria-label="channel" value={c.channel ?? "EMAIL"} onChange={(e) => onChange({ ...c, channel: e.target.value })}>{["EMAIL", "WHATSAPP", "SMS"].map((x) => <option key={x}>{x}</option>)}</Select>}
      {c.type === "CONSENT" && <Select className="h-8 w-40" aria-label="purpose" value={c.purpose ?? "MARKETING"} onChange={(e) => onChange({ ...c, purpose: e.target.value })}>{PURPOSES.map((p) => <option key={p} value={p}>{t(`purposes.${p}`)}</option>)}</Select>}
      {["HAS_TAG", "NOT_HAS_TAG"].includes(c.type) && <Input className="h-8 w-32" aria-label="tag" value={c.tag ?? ""} onChange={(e) => onChange({ ...c, tag: e.target.value })} />}
      {["NOT_MESSAGED_RECENTLY", "HAS_EVENT"].includes(c.type) && <Input className="h-8 w-20" type="number" aria-label="days" value={c.days ?? 7} onChange={(e) => onChange({ ...c, days: Number(e.target.value) })} />}
      {c.type === "HAS_EVENT" && <Input className="h-8 w-40" aria-label="event" value={c.eventType ?? ""} onChange={(e) => onChange({ ...c, eventType: e.target.value })} />}
      {c.type === "COUNTRY_IN" && <Input className="h-8 w-32" aria-label="countries" placeholder="BR,PT" value={(c.countries ?? []).join(",")} onChange={(e) => onChange({ ...c, countries: e.target.value.split(",").map((s) => s.trim().toUpperCase()).filter(Boolean) })} />}
      {["LIFETIME_VALUE_GTE", "BUDGET_AVAILABLE"].includes(c.type) && <Input className="h-8 w-28" type="number" aria-label="amount (minor units)" value={c.amountMinor ?? 0} onChange={(e) => onChange({ ...c, amountMinor: Number(e.target.value) })} />}
      {c.type === "WITHIN_HOURS" && (<><Input className="h-8 w-24" type="time" aria-label="from" value={c.from ?? "09:00"} onChange={(e) => onChange({ ...c, from: e.target.value })} /><Input className="h-8 w-24" type="time" aria-label="to" value={c.to ?? "18:00"} onChange={(e) => onChange({ ...c, to: e.target.value })} /></>)}
      <Button size="sm" variant="ghost" onClick={onRemove} aria-label="remove">✕</Button>
    </div>
  );
}

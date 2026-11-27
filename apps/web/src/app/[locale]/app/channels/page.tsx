"use client";
import { useEffect, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useLocale, useTranslations } from "next-intl";
import { LANGUAGES, LANGUAGE_NAMES, languageOf } from "@/i18n/languages";
import { api, errorMessage } from "@/lib/api";
import { useCan } from "@/lib/auth";
import { Alert, Badge, Button, Card, CardTitle, Field, Input, PageHeader, Select, StatusBadge, Table, Td, Textarea } from "@/components/ui";

interface Sender { id: string; kind: string; value: string; status: string; verificationData?: { dnsRecords?: { type: string; name: string; value: string; note?: string }[]; steps?: string[] }; qualityRating?: string; messagingLimit?: string }
interface ChannelView { channel: string; includedInPlan: boolean; status: string; statusReason?: string; actionRequired?: string; provider?: string; senders: Sender[];
  capabilities: { capability: string; available: boolean; requires?: string; notes?: string }[] }

export default function ChannelsPage() {
  const t = useTranslations("channels");
  const q = useQuery({ queryKey: ["channels"], queryFn: () => api<ChannelView[]>("/channels") });
  const byKey = Object.fromEntries((q.data ?? []).map((c) => [c.channel, c]));
  return (
    <div className="space-y-4">
      <PageHeader title={t("title")} description={t("subtitle")} />
      <div className="grid gap-4 lg:grid-cols-2">
        {byKey.EMAIL && <ChannelCard c={byKey.EMAIL}><EmailSetup c={byKey.EMAIL} onChange={() => q.refetch()} /></ChannelCard>}
        {byKey.WHATSAPP && <ChannelCard c={byKey.WHATSAPP}><WhatsAppSetup onChange={() => q.refetch()} /></ChannelCard>}
        {byKey.SMS && <ChannelCard c={byKey.SMS}><SmsSetup c={byKey.SMS} onChange={() => q.refetch()} /></ChannelCard>}
        {byKey.VOICE && <ChannelCard c={byKey.VOICE}><p className="text-sm text-muted-foreground">Missed-call detection uses your SMS/Voice number (see Calls). Monitoring starts after a real test call.</p></ChannelCard>}
      </div>
      <Templates />
    </div>
  );
}

function ChannelCard({ c, children }: { c: ChannelView; children: React.ReactNode }) {
  const t = useTranslations("channels");
  const canManage = useCan("manage");
  return (
    <Card>
      <div className="flex items-center justify-between"><CardTitle>{c.channel}</CardTitle><StatusBadge status={c.status} /></div>
      {!c.includedInPlan && <div className="mt-2"><Badge tone="warning">{t("notInPlan")}</Badge></div>}
      {c.statusReason && <p className="mt-2 text-sm"><span className="text-muted-foreground">{t("reason")}:</span> {c.statusReason}</p>}
      {c.actionRequired && <p className="mt-1 text-sm"><span className="text-muted-foreground">{t("actionRequired")}:</span> {c.actionRequired}</p>}
      {c.senders.map((s) => (
        <div key={s.id} className="mt-3 rounded-md border border-border p-2 text-sm">
          <div className="flex flex-wrap items-center gap-2"><Badge>{s.kind}</Badge><span className="font-mono">{s.value}</span><StatusBadge status={s.status} />
            {s.qualityRating && <Badge tone={s.qualityRating === "GREEN" ? "success" : "warning"}>quality {s.qualityRating}</Badge>}{s.messagingLimit && <Badge>{s.messagingLimit}</Badge>}</div>
          {s.verificationData?.dnsRecords && (
            <div className="mt-2"><p className="text-xs font-medium">{t("dnsRecords")}</p>
              <ul className="mt-1 space-y-1 font-mono text-xs">{s.verificationData.dnsRecords.map((r) => <li key={r.name} className="break-all">{r.type} {r.name} → {r.value}{r.note ? ` (${r.note})` : ""}</li>)}</ul></div>
          )}
          {s.verificationData?.steps && <ul className="mt-2 list-disc pl-5 text-xs">{s.verificationData.steps.map((x) => <li key={x}>{x}</li>)}</ul>}
        </div>
      ))}
      <details className="mt-3 text-xs"><summary className="cursor-pointer text-muted-foreground">{t("capabilities")}</summary>
        <ul className="mt-1 space-y-1">{c.capabilities.map((x) => <li key={x.capability}>{x.available ? "✓" : "✕"} {x.capability}{x.requires ? ` — ${x.requires}` : ""}{x.notes ? ` (${x.notes})` : ""}</li>)}</ul>
      </details>
      {canManage && c.includedInPlan && <div className="mt-4 border-t border-border pt-4">{children}</div>}
    </Card>
  );
}

function EmailSetup({ c, onChange }: { c: ChannelView; onChange: () => void }) {
  const t = useTranslations("channels");
  const [f, setF] = useState({ domain: "", localPart: "contato", displayName: "" });
  const [error, setError] = useState<string | null>(null);
  const act = async (fn: () => Promise<unknown>) => { setError(null); try { await fn(); onChange(); } catch (e) { setError(errorMessage(e)); } };
  return (
    <div className="space-y-3">
      <div className="grid gap-2 sm:grid-cols-3">
        <Field label={t("domain")} htmlFor="ed"><Input id="ed" placeholder="mystore.com" value={f.domain} onChange={(e) => setF({ ...f, domain: e.target.value })} /></Field>
        <Field label={t("localPart")} htmlFor="el"><Input id="el" value={f.localPart} onChange={(e) => setF({ ...f, localPart: e.target.value })} /></Field>
        <Field label={t("displayName")} htmlFor="en"><Input id="en" value={f.displayName} onChange={(e) => setF({ ...f, displayName: e.target.value })} /></Field>
      </div>
      <div className="flex flex-wrap gap-2">
        <Button size="sm" onClick={() => act(() => api("/channels/email/domain", { body: f }))}>{t("ownDomain")}</Button>
        <Button size="sm" variant="outline" onClick={() => act(() => api("/channels/email/platform-sender", { body: { displayName: f.displayName || "Store" } }))}>{t("platformSender")}</Button>
        {c.status === "PENDING_VERIFICATION" && <Button size="sm" variant="secondary" onClick={() => act(() => api("/channels/email/refresh", { method: "POST" }))}>{t("verify")}</Button>}
      </div>
      {error && <Alert tone="danger" title={error} />}
    </div>
  );
}

declare global {
  interface Window { FB?: { init: (o: object) => void; login: (cb: (r: { authResponse?: { code?: string } }) => void, o: object) => void }; fbAsyncInit?: () => void }
}

function WhatsAppSetup({ onChange }: { onChange: () => void }) {
  const t = useTranslations("channels");
  const cfg = useQuery({ queryKey: ["wa-cfg"], queryFn: () => api<{ configured: boolean; appId?: string; configId?: string; graphVersion?: string }>("/channels/whatsapp/signup-config") });
  const session = useRef<{ wabaId?: string; phoneNumberId?: string; coexistence: boolean }>({ coexistence: false });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    const onMsg = (ev: MessageEvent) => {
      if (!/facebook\.com$/.test(new URL(ev.origin).hostname)) return;
      try {
        const d = typeof ev.data === "string" ? JSON.parse(ev.data) : ev.data;
        if (d?.type === "WA_EMBEDDED_SIGNUP" && d.data) {
          session.current = { wabaId: d.data.waba_id, phoneNumberId: d.data.phone_number_id, coexistence: d.event === "FINISH_WHATSAPP_BUSINESS_APP_ONBOARDING" };
        }
      } catch { /* non-JSON message */ }
    };
    window.addEventListener("message", onMsg);
    return () => window.removeEventListener("message", onMsg);
  }, []);
  if (!cfg.data) return null;
  if (!cfg.data.configured) return <Alert tone="info" title="WhatsApp Embedded Signup is not configured on this platform yet (Meta app id / configuration id)." />;
  const launch = () => {
    setError(null);
    const start = () => {
      window.FB!.init({ appId: cfg.data!.appId, autoLogAppEvents: true, xfbml: false, version: cfg.data!.graphVersion });
      window.FB!.login(async (r) => {
        const code = r.authResponse?.code;
        if (!code) { setError("Signup was not completed"); return; }
        setBusy(true);
        try { await api("/channels/whatsapp/embedded-signup", { body: { code, ...session.current } }); onChange(); }
        catch (e) { setError(errorMessage(e)); } finally { setBusy(false); }
      }, { config_id: cfg.data!.configId, response_type: "code", override_default_response_type: true, extras: { setup: {}, sessionInfoVersion: "3" } });
    };
    if (window.FB) { start(); return; }
    const s = document.createElement("script");
    s.src = "https://connect.facebook.net/en_US/sdk.js"; s.async = true; s.crossOrigin = "anonymous"; s.onload = start;
    document.body.appendChild(s);
  };
  return (
    <div className="space-y-2">
      <p className="text-sm text-muted-foreground">{t("waHelp")}</p>
      <div className="flex gap-2">
        <Button onClick={launch} loading={busy}>{t("waConnect")}</Button>
        <Button variant="outline" onClick={async () => { try { await api("/channels/whatsapp/refresh", { method: "POST" }); onChange(); } catch (e) { setError(errorMessage(e)); } }}>{t("verify")}</Button>
      </div>
      {error && <Alert tone="danger" title={error} />}
    </div>
  );
}

function SmsSetup({ c, onChange }: { c: ChannelView; onChange: () => void }) {
  const t = useTranslations("channels");
  const [country, setCountry] = useState("US");
  const [area, setArea] = useState("");
  const [numbers, setNumbers] = useState<{ phoneNumber: string; locality?: string; capabilities: string[] }[]>([]);
  const [svc, setSvc] = useState("");
  const [error, setError] = useState<string | null>(null);
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-end gap-2">
        <Field label="Country" htmlFor="sc"><Input id="sc" className="w-20" value={country} onChange={(e) => setCountry(e.target.value.toUpperCase())} /></Field>
        <Field label={t("areaCode")} htmlFor="sa"><Input id="sa" className="w-28" value={area} onChange={(e) => setArea(e.target.value)} /></Field>
        <Button size="sm" onClick={async () => { setError(null); try { setNumbers(await api(`/channels/sms/available-numbers?country=${country}&areaCode=${area}`)); } catch (e) { setError(errorMessage(e)); } }}>{t("smsSearch")}</Button>
      </div>
      {numbers.length > 0 && (
        <ul className="max-h-48 space-y-1 overflow-y-auto text-sm">{numbers.map((n) => (
          <li key={n.phoneNumber} className="flex items-center justify-between rounded border border-border px-2 py-1">
            <span className="font-mono">{n.phoneNumber}</span><span className="text-xs text-muted-foreground">{n.locality} · {n.capabilities.join(",")}</span>
            <Button size="sm" variant="outline" onClick={async () => { try { await api("/channels/sms/numbers", { body: { phoneNumber: n.phoneNumber } }); setNumbers([]); onChange(); } catch (e) { setError(errorMessage(e)); } }}>{t("buy")}</Button>
          </li>))}</ul>
      )}
      {c.status === "PENDING_APPROVAL" && (
        <div className="flex flex-wrap items-end gap-2">
          <Field label={`${t("a2p")} — ${t("messagingService")}`} htmlFor="ms"><Input id="ms" placeholder="MG…" value={svc} onChange={(e) => setSvc(e.target.value)} /></Field>
          <Button size="sm" onClick={async () => { try { await api("/channels/sms/a2p/refresh", { body: { messagingServiceSid: svc } }); onChange(); } catch (e) { setError(errorMessage(e)); } }}>{t("verify")}</Button>
        </div>
      )}
      {error && <Alert tone="danger" title={error} />}
    </div>
  );
}

function Templates() {
  const uiLocale = useLocale();
  const t = useTranslations("channels");
  const q = useQuery({ queryKey: ["templates-all"], queryFn: () => api<{ id: string; channel: string; name: string; locale: string; purpose: string; waCategory?: string; status: string; rejectionReason?: string }[]>("/templates") });
  const [f, setF] = useState({ channel: "EMAIL", name: "", locale: (languageOf(uiLocale) ?? "en") as string, purpose: "MARKETING", waCategory: "MARKETING", subject: "", body: "" });
  const [error, setError] = useState<string | null>(null);
  return (
    <Card>
      <CardTitle>{t("templates")}</CardTitle>
      <div className="mt-3">
        <Table head={["Name", "Channel", "Locale", "Purpose", "Status", ""]}>
          {q.data?.map((x) => (
            <tr key={x.id}><Td>{x.name}</Td><Td>{x.channel}</Td><Td>{x.locale}</Td><Td>{x.purpose}{x.waCategory ? ` · ${x.waCategory}` : ""}</Td>
              <Td><StatusBadge status={x.status} />{x.rejectionReason && <p className="text-xs text-danger">{x.rejectionReason}</p>}</Td>
              <Td>{x.channel === "WHATSAPP" && ["DRAFT", "REJECTED"].includes(x.status) && <Button size="sm" onClick={async () => { try { await api(`/templates/${x.id}/submit`, { method: "POST" }); await q.refetch(); } catch (e) { setError(errorMessage(e)); } }}>{t("submit")}</Button>}</Td></tr>
          ))}
        </Table>
      </div>
      <div className="mt-4 grid gap-2 sm:grid-cols-3">
        <Select aria-label="channel" value={f.channel} onChange={(e) => setF({ ...f, channel: e.target.value })}><option>EMAIL</option><option>WHATSAPP</option><option>SMS</option></Select>
        <Input aria-label="name" placeholder="name" value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} />
        <Select aria-label="locale" value={f.locale} onChange={(e) => setF({ ...f, locale: e.target.value })}>{LANGUAGES.map((l) => <option key={l} value={l}>{LANGUAGE_NAMES[l]}</option>)}</Select>
        <Select aria-label="purpose" value={f.purpose} onChange={(e) => setF({ ...f, purpose: e.target.value })}>{["TRANSACTIONAL", "SERVICE_REMINDER", "CUSTOMER_CARE", "MARKETING", "REACTIVATION"].map((p) => <option key={p}>{p}</option>)}</Select>
        {f.channel === "WHATSAPP" && <Select aria-label={t("category")} value={f.waCategory} onChange={(e) => setF({ ...f, waCategory: e.target.value })}><option>MARKETING</option><option>UTILITY</option></Select>}
        {f.channel === "EMAIL" && <Input aria-label="subject" placeholder="subject" value={f.subject} onChange={(e) => setF({ ...f, subject: e.target.value })} />}
        <div className="sm:col-span-3"><Textarea aria-label="body" placeholder="Hi {{contact.first_name}}…" value={f.body} onChange={(e) => setF({ ...f, body: e.target.value })} /></div>
      </div>
      {error && <Alert tone="danger" title={error} />}
      <Button className="mt-2" size="sm" onClick={async () => { setError(null); try {
        await api("/templates", { body: { ...f, waCategory: f.channel === "WHATSAPP" ? f.waCategory : null, subject: f.channel === "EMAIL" ? f.subject : null } }); await q.refetch();
      } catch (e) { setError(errorMessage(e)); } }}>{t("newTemplate")}</Button>
    </Card>
  );
}

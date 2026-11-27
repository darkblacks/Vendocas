"use client";
import { useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { useLocale, useTranslations } from "next-intl";
import { api, errorMessage } from "@/lib/api";
import { dateTime } from "@/lib/format";
import { LANGUAGES, LANGUAGE_NAMES, languageOf } from "@/i18n/languages";
import { useAuth, useCan } from "@/lib/auth";
import { Alert, Badge, Button, Card, CardTitle, Field, Input, Select, StatusBadge, Switch, Table, Tabs, TabsContent, TabsList, TabsTrigger, Td, Textarea, PageHeader } from "@/components/ui";

export default function SettingsPage() {
  const t = useTranslations("settings");
  const tab = useSearchParams().get("tab") ?? "organization";
  return (
    <div>
      <PageHeader title={t("title")} />
      <Tabs defaultValue={tab}>
        <TabsList>
          {["organization", "limits", "members", "security", "integrations", "privacy", "audit", "notifications", "support"].map((k) => (
            <TabsTrigger key={k} value={k}>{k === "notifications" ? "🔔" : k === "support" ? "Support" : t(k as never)}</TabsTrigger>
          ))}
        </TabsList>
        <TabsContent value="organization"><OrgTab /></TabsContent>
        <TabsContent value="limits"><LimitsTab /></TabsContent>
        <TabsContent value="members"><MembersTab /></TabsContent>
        <TabsContent value="security"><SecurityTab /></TabsContent>
        <TabsContent value="integrations"><IntegrationsTab /></TabsContent>
        <TabsContent value="privacy"><PrivacyTab /></TabsContent>
        <TabsContent value="audit"><AuditTab /></TabsContent>
        <TabsContent value="notifications"><NotificationsTab /></TabsContent>
        <TabsContent value="support"><SupportTab /></TabsContent>
      </Tabs>
    </div>
  );
}

function useAct() {
  const [msg, setMsg] = useState<{ tone: "success" | "danger"; text: string } | null>(null);
  const act = async (fn: () => Promise<unknown>, ok = "Saved") => { setMsg(null); try { await fn(); setMsg({ tone: "success", text: ok }); } catch (e) { setMsg({ tone: "danger", text: errorMessage(e) }); } };
  return { msg, act, view: msg && <Alert tone={msg.tone} title={msg.text} /> };
}

type Org = { legalName: string; tradeName: string; locale: string; timeZone: string; contactEmail: string; financeEmail?: string; contactPhone?: string; addressLine1?: string; addressLine2?: string; city?: string; region?: string; postalCode?: string; countryCode: string; billingCurrency: string; dataRegion: string; publicKey: string };
type Tax = { countryCode: string; taxIdType: string; taxId?: string; taxIdStatus: string; customerType: string; municipalRegistration?: string; usState?: string };

function OrgTab() {
  const t = useTranslations();
  const org = useQuery({ queryKey: ["org"], queryFn: () => api<Org>("/organizations/current") });
  const tax = useQuery({ queryKey: ["tax"], queryFn: () => api<Tax>("/organizations/current/tax-profile") });
  const [f, setF] = useState<Org | null>(null);
  const [tx, setTx] = useState<Tax | null>(null);
  useEffect(() => { if (org.data) setF(org.data); }, [org.data]);
  useEffect(() => { if (tax.data) setTx(tax.data); }, [tax.data]);
  const { act, view } = useAct();
  if (!f || !tx) return null;
  const field = (k: keyof Org, label: string) => <Field label={label} htmlFor={k}><Input id={k} value={(f[k] as string) ?? ""} onChange={(e) => setF({ ...f, [k]: e.target.value })} /></Field>;
  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <Card className="grid gap-3">
        <CardTitle>{t("settings.organization")} <Badge>{f.countryCode} · {f.billingCurrency} · region {f.dataRegion}</Badge></CardTitle>
        {field("legalName", t("setup.legalName"))}{field("tradeName", t("setup.tradeName"))}{field("contactEmail", t("setup.contactEmail"))}{field("financeEmail", t("setup.financeEmail"))}
        {field("contactPhone", t("common.phone"))}{field("addressLine1", t("setup.address"))}{field("city", t("setup.city"))}{field("region", t("setup.region"))}{field("postalCode", t("setup.postalCode"))}
        {field("timeZone", t("common.timeZone"))}<Field label={t("common.language")} htmlFor="locale"><Select id="locale" value={languageOf(f.locale) ?? "en"} onChange={(e) => setF({ ...f, locale: e.target.value })}>
          {LANGUAGES.map((l) => <option key={l} value={l}>{LANGUAGE_NAMES[l]}</option>)}</Select></Field>
        <Button onClick={() => act(() => api("/organizations/current", { method: "PUT", body: f }))}>{t("common.save")}</Button>
        {view}
      </Card>
      <Card className="grid gap-3 self-start">
        <CardTitle>{t("setup.taxId")} <StatusBadge status={tx.taxIdStatus} /></CardTitle>
        <Field label={t("setup.taxIdType")} htmlFor="tt"><Input id="tt" value={tx.taxIdType} onChange={(e) => setTx({ ...tx, taxIdType: e.target.value })} /></Field>
        <Field label={t("setup.taxId")} htmlFor="tv"><Input id="tv" value={tx.taxId ?? ""} onChange={(e) => setTx({ ...tx, taxId: e.target.value })} /></Field>
        {tx.countryCode === "BR" && <Field label="Inscrição municipal" htmlFor="im"><Input id="im" value={tx.municipalRegistration ?? ""} onChange={(e) => setTx({ ...tx, municipalRegistration: e.target.value })} /></Field>}
        <div className="flex gap-2">
          <Button onClick={() => act(() => api("/organizations/current/tax-profile", { method: "PUT", body: { type: tx.taxIdType, value: tx.taxId, customerType: tx.customerType, municipalRegistration: tx.municipalRegistration, usState: tx.usState } }).then(() => tax.refetch()))}>{t("common.save")}</Button>
          {tx.taxIdType === "EU_VAT" && <Button variant="outline" onClick={() => act(() => api("/organizations/current/tax-profile/verify", { method: "POST" }).then(() => tax.refetch()))}>{t("settings.verifyVat")}</Button>}
        </div>
      </Card>
    </div>
  );
}

function LimitsTab() {
  const t = useTranslations("settings");
  const q = useQuery({ queryKey: ["settings"], queryFn: () => api<Record<string, unknown>>("/organizations/current/settings") });
  const [f, setF] = useState<Record<string, unknown> | null>(null);
  useEffect(() => { if (q.data) setF(q.data); }, [q.data]);
  const { act, view } = useAct();
  if (!f) return null;
  const num = (k: string, label: string, hint?: string) => <Field label={label} htmlFor={k} hint={hint}><Input id={k} type="number" value={String(f[k] ?? "")} onChange={(e) => setF({ ...f, [k]: Number(e.target.value) })} /></Field>;
  return (
    <Card className="grid max-w-2xl gap-3 sm:grid-cols-2">
      <Field label={t("quietStart")} htmlFor="qs"><Input id="qs" type="time" value={String(f.quietHoursStart).slice(0, 5)} onChange={(e) => setF({ ...f, quietHoursStart: e.target.value })} /></Field>
      <Field label={t("quietEnd")} htmlFor="qe"><Input id="qe" type="time" value={String(f.quietHoursEnd).slice(0, 5)} onChange={(e) => setF({ ...f, quietHoursEnd: e.target.value })} /></Field>
      {num("defaultFrequencyCap", t("frequencyCap"))}{num("dataRetentionDays", t("retention"))}
      {num("dailySpendLimitMinor", t("dailyLimit"), "minor units")}{num("monthlySpendLimitMinor", t("monthlyLimit"), "minor units")}{num("lowBalanceThresholdMinor", t("lowBalance"), "minor units")}
      <div className="sm:col-span-2"><Field label={t("alertEmail")} htmlFor="alertEmail" hint={t("alertEmailHint")}>
        <Input id="alertEmail" type="email" maxLength={254} value={String(f.alertEmail ?? "")} onChange={(e) => setF({ ...f, alertEmail: e.target.value })} />
      </Field></div>
      <div className="sm:col-span-2"><Field label={t("alertContact")} htmlFor="alertContact" hint={t("alertContactHint")}>
        <Input id="alertContact" maxLength={200} value={String(f.alertContact ?? "")} onChange={(e) => setF({ ...f, alertContact: e.target.value })} />
      </Field></div>
      <label className="flex items-center gap-3 text-sm"><Switch checked={!!f.requireMfaForAdmins} onCheckedChange={(v) => setF({ ...f, requireMfaForAdmins: v })} />{t("requireMfa")}</label>
      <div className="sm:col-span-2"><Button onClick={() => act(() => api("/organizations/current/settings", { method: "PUT", body: f }))}>Save</Button></div>
      <div className="sm:col-span-2">{view}</div>
    </Card>
  );
}

function MembersTab() {
  const t = useTranslations("settings");
  const canManage = useCan("manage");
  const q = useQuery({ queryKey: ["members"], queryFn: () => api<{ userId: string; email: string; fullName: string; role: string; status: string; mfaEnabled: boolean }[]>("/members") });
  const [inv, setInv] = useState({ email: "", role: "OPERATOR" });
  const { act, view } = useAct();
  return (
    <div className="space-y-4">
      <Table head={["Name", "Email", t("role"), "2FA", ""]}>
        {q.data?.map((m) => (
          <tr key={m.userId}><Td>{m.fullName}</Td><Td>{m.email}</Td>
            <Td>{canManage ? <Select className="h-8" aria-label={t("role")} value={m.role} onChange={(e) => act(() => api(`/members/${m.userId}/role`, { method: "PUT", body: { role: e.target.value } }).then(() => q.refetch()))}>
              {["OWNER", "ADMIN", "FINANCE", "OPERATOR", "READ_ONLY"].map((r) => <option key={r}>{r}</option>)}</Select> : m.role}</Td>
            <Td>{m.mfaEnabled ? "✓" : "—"}</Td>
            <Td>{canManage && <Button size="sm" variant="ghost" onClick={() => confirm("Remove?") && act(() => api(`/members/${m.userId}`, { method: "DELETE" }).then(() => q.refetch()))}>✕</Button>}</Td></tr>
        ))}
      </Table>
      {canManage && (
        <Card className="flex flex-wrap items-end gap-2">
          <Field label="Email" htmlFor="ie"><Input id="ie" type="email" value={inv.email} onChange={(e) => setInv({ ...inv, email: e.target.value })} /></Field>
          <Field label={t("role")} htmlFor="ir"><Select id="ir" value={inv.role} onChange={(e) => setInv({ ...inv, role: e.target.value })}>{["ADMIN", "FINANCE", "OPERATOR", "READ_ONLY"].map((r) => <option key={r}>{r}</option>)}</Select></Field>
          <Button onClick={() => act(() => api("/members/invitations", { body: inv }), "Invitation sent")}>{t("invite")}</Button>
        </Card>
      )}
      {view}
    </div>
  );
}

function SecurityTab() {
  const t = useTranslations("settings");
  const { me, reload } = useAuth();
  const [setup, setSetup] = useState<{ secret: string; otpauthUri: string } | null>(null);
  const [code, setCode] = useState("");
  const [pw, setPw] = useState({ currentPassword: "", newPassword: "" });
  const { act, view } = useAct();
  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <Card className="space-y-3">
        <CardTitle>{me?.mfaEnabled ? t("mfaOn") : t("mfaEnable")}</CardTitle>
        {!me?.mfaEnabled && !setup && <Button onClick={() => act(async () => setSetup(await api("/auth/mfa/setup", { method: "POST", tenant: false })), "")}>{t("mfaEnable")}</Button>}
        {setup && (<>
          <p className="text-sm">{t("mfaSecret")}</p>
          <code className="block break-all rounded bg-muted p-2 text-sm">{setup.secret}</code>
          <a className="text-xs text-primary" href={setup.otpauthUri}>otpauth://…</a>
          <Input aria-label="code" inputMode="numeric" maxLength={6} value={code} onChange={(e) => setCode(e.target.value)} />
          <Button onClick={() => act(async () => { await api("/auth/mfa/enable", { body: { code }, tenant: false }); setSetup(null); await reload(); })}>{t("mfaEnable")}</Button>
        </>)}
        {me?.mfaEnabled && (<div className="flex gap-2"><Input aria-label="code" maxLength={6} value={code} onChange={(e) => setCode(e.target.value)} />
          <Button variant="danger" onClick={() => act(async () => { await api("/auth/mfa/disable", { body: { code }, tenant: false }); await reload(); })}>Disable</Button></div>)}
      </Card>
      <Card className="space-y-3">
        <CardTitle>{t("changePassword")}</CardTitle>
        <Field label={t("currentPassword")} htmlFor="cp"><Input id="cp" type="password" value={pw.currentPassword} onChange={(e) => setPw({ ...pw, currentPassword: e.target.value })} /></Field>
        <Field label={t("newPassword")} htmlFor="np"><Input id="np" type="password" minLength={10} value={pw.newPassword} onChange={(e) => setPw({ ...pw, newPassword: e.target.value })} /></Field>
        <Button onClick={() => act(() => api("/auth/password/change", { body: pw, tenant: false }))}>Save</Button>
      </Card>
      <div className="lg:col-span-2">{view}</div>
    </div>
  );
}

function IntegrationsTab() {
  const t = useTranslations("settings");
  const org = useQuery({ queryKey: ["org"], queryFn: () => api<Org>("/organizations/current") });
  const keys = useQuery({ queryKey: ["api-keys"], queryFn: () => api<{ id: string; name: string; prefix: string; scopes: string[]; status: string; lastUsedAt?: string }[]>("/integrations/api-keys") });
  const [created, setCreated] = useState<string | null>(null);
  const [secret, setSecret] = useState<string | null>(null);
  const [name, setName] = useState("Website backend");
  const [scopes, setScopes] = useState<string[]>(["contacts:write", "events:write", "orders:write"]);
  const { act, view } = useAct();
  const origin = typeof window !== "undefined" ? window.location.origin : "";
  return (
    <div className="space-y-4">
      <Card className="space-y-3">
        <CardTitle>{t("apiKeys")}</CardTitle>
        <Table head={["Name", "Prefix", t("scopes"), "Status", "Last used", ""]}>
          {keys.data?.map((k) => <tr key={k.id}><Td>{k.name}</Td><Td className="font-mono">vk_{k.prefix}_…</Td><Td className="text-xs">{k.scopes.join(", ")}</Td><Td><StatusBadge status={k.status} /></Td>
            <Td className="text-xs">{k.lastUsedAt ?? "—"}</Td><Td>{k.status === "ACTIVE" && <Button size="sm" variant="ghost" onClick={() => act(() => api(`/integrations/api-keys/${k.id}`, { method: "DELETE" }).then(() => keys.refetch()))}>revoke</Button>}</Td></tr>)}
        </Table>
        <div className="flex flex-wrap items-end gap-2">
          <Field label="Name" htmlFor="kn"><Input id="kn" value={name} onChange={(e) => setName(e.target.value)} /></Field>
          <div className="flex flex-wrap gap-2 text-xs">{["contacts:write", "events:write", "orders:write", "appointments:write", "calls:write"].map((s) => (
            <label key={s} className="flex items-center gap-1"><input type="checkbox" checked={scopes.includes(s)} onChange={(e) => setScopes(e.target.checked ? [...scopes, s] : scopes.filter((x) => x !== s))} />{s}</label>))}</div>
          <Button onClick={() => act(async () => { const r = await api<{ key: string }>("/integrations/api-keys", { body: { name, scopes } }); setCreated(r.key); await keys.refetch(); }, "")}>{t("newKey")}</Button>
        </div>
        {created && <Alert tone="warning" title={t("keyOnce")}><code className="break-all">{created}</code></Alert>}
      </Card>
      <Card className="space-y-3">
        <CardTitle>{t("tracking")}</CardTitle>
        <p className="text-sm">{t("publicKey")}: <code>{org.data?.publicKey}</code></p>
        <Button variant="outline" onClick={() => confirm("Existing signed identities stop validating. Continue?") && act(async () => setSecret((await api<{ secret: string }>("/organizations/current/tracking-secret/rotate", { method: "POST" })).secret), "")}>{t("rotateSecret")}</Button>
        {secret && <Alert tone="warning" title={t("secretOnce")}><code className="break-all">{secret}</code></Alert>}
        <Field label={t("snippet")} htmlFor="snip"><Textarea id="snip" readOnly className="font-mono text-xs" rows={6}
          value={`<script src="${origin}/sdk/vendocas.js"></script>\n<script>\n  Vendocas.init({ pk: "${org.data?.publicKey ?? "pk_…"}", endpoint: "${origin}", consent: false });\n  // call Vendocas.setConsent(true) from your consent banner\n</script>`} /></Field>
      </Card>
      {view}
    </div>
  );
}

function PrivacyTab() {
  const t = useTranslations("settings");
  const locale = useLocale();
  const q = useQuery({ queryKey: ["privacy"], queryFn: () => api<{ id: string; contactId?: string; type: string; status: string; requesterEmail?: string; dueAt: string; createdAt: string }[]>("/privacy/requests") });
  const [f, setF] = useState({ contactId: "", requesterEmail: "", type: "ACCESS" });
  const { act, view } = useAct();
  return (
    <div className="space-y-4">
      <Card className="flex flex-wrap items-end gap-2">
        <Field label="Contact ID" htmlFor="pc"><Input id="pc" value={f.contactId} onChange={(e) => setF({ ...f, contactId: e.target.value })} /></Field>
        <Field label="Requester email" htmlFor="pe"><Input id="pe" type="email" value={f.requesterEmail} onChange={(e) => setF({ ...f, requesterEmail: e.target.value })} /></Field>
        <Field label="Type" htmlFor="pt"><Select id="pt" value={f.type} onChange={(e) => setF({ ...f, type: e.target.value })}>{["ACCESS", "EXPORT", "RECTIFICATION", "ERASURE", "RESTRICTION", "OBJECTION"].map((x) => <option key={x}>{x}</option>)}</Select></Field>
        <Button onClick={() => act(() => api("/privacy/requests", { body: { ...f, contactId: f.contactId || null, requesterEmail: f.requesterEmail || null } }).then(() => q.refetch()))}>{t("newRequest")}</Button>
      </Card>
      <Table head={["Type", "Status", "Requester", "Due", "Created", ""]}>
        {q.data?.map((r) => <tr key={r.id}><Td>{r.type}</Td><Td><StatusBadge status={r.status} /></Td><Td>{r.requesterEmail}</Td><Td className="text-xs">{dateTime(r.dueAt, locale)}</Td><Td className="text-xs">{dateTime(r.createdAt, locale)}</Td>
          <Td>{r.status !== "COMPLETED" && <Button size="sm" onClick={() => act(async () => {
            const res = await api<{ data?: unknown }>(`/privacy/requests/${r.id}/execute`, { method: "POST" });
            if (res.data) { const u = URL.createObjectURL(new Blob([JSON.stringify(res.data, null, 2)], { type: "application/json" })); const a = document.createElement("a"); a.href = u; a.download = `privacy-${r.id}.json`; a.click(); }
            await q.refetch();
          })}>{t("execute")}</Button>}</Td></tr>)}
      </Table>
      {view}
    </div>
  );
}

function AuditTab() {
  const locale = useLocale();
  const q = useQuery({ queryKey: ["audit"], queryFn: () => api<{ action: string; actor_type: string; actor_email?: string; target_type?: string; target_id?: string; created_at: string }[]>("/audit-logs") });
  return <Table head={["When", "Action", "Actor", "Target"]}>{q.data?.map((a, i) => <tr key={i}><Td className="text-xs">{dateTime(a.created_at, locale)}</Td><Td>{a.action}</Td><Td>{a.actor_email ?? a.actor_type}</Td><Td className="text-xs">{a.target_type} {a.target_id}</Td></tr>)}</Table>;
}

function NotificationsTab() {
  const locale = useLocale();
  const q = useQuery({ queryKey: ["notifications-all"], queryFn: () => api<{ id: string; severity: string; title: string; body?: string; read: boolean; createdAt: string }[]>("/notifications") });
  return (
    <ul className="space-y-2">{q.data?.map((n) => (
      <li key={n.id}><Alert tone={n.severity === "CRITICAL" ? "danger" : n.severity === "WARNING" ? "warning" : "info"} title={`${n.title} · ${dateTime(n.createdAt, locale)}`}>
        {n.body}{!n.read && <Button size="sm" variant="ghost" onClick={async () => { await api(`/notifications/${n.id}/read`, { method: "POST" }); await q.refetch(); }}>✓</Button>}
      </Alert></li>))}</ul>
  );
}

function SupportTab() {
  const q = useQuery({ queryKey: ["tickets"], queryFn: () => api<{ id: string; subject: string; status: string; created_at: string }[]>("/support/tickets") });
  const [f, setF] = useState({ subject: "", body: "", category: "GENERAL" });
  const { act, view } = useAct();
  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <Card className="space-y-3">
        <Field label="Subject" htmlFor="ss"><Input id="ss" value={f.subject} onChange={(e) => setF({ ...f, subject: e.target.value })} /></Field>
        <Field label="Category" htmlFor="sc"><Select id="sc" value={f.category} onChange={(e) => setF({ ...f, category: e.target.value })}>{["GENERAL", "BILLING", "CHANNELS", "PRIVACY", "ABUSE"].map((c) => <option key={c}>{c}</option>)}</Select></Field>
        <Field label="Message" htmlFor="sb"><Textarea id="sb" value={f.body} onChange={(e) => setF({ ...f, body: e.target.value })} /></Field>
        <Button onClick={() => act(() => api("/support/tickets", { body: f }).then(() => q.refetch()), "Ticket opened")}>Send</Button>
        {view}
      </Card>
      <Card><ul className="space-y-2 text-sm">{q.data?.map((x) => <li key={x.id} className="flex justify-between"><span>{x.subject}</span><StatusBadge status={x.status} /></li>)}</ul></Card>
    </div>
  );
}

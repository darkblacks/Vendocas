"use client";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { Link } from "@/i18n/navigation";
import { api, errorMessage } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { Alert, Button, Card, CardTitle, Field, Input, PageHeader, Select, Spinner, StatusBadge, Table, Tabs, TabsContent, TabsList, TabsTrigger, Td } from "@/components/ui";

/** Platform operator back-office. The API enforces platform role + MFA on every request. */
export default function AdminPage() {
  const t = useTranslations("admin");
  const { status, me } = useAuth();
  if (status === "loading") return <Spinner />;
  if (!me?.platformRole) return <div className="p-6"><Alert tone="danger" title="Platform administrators only" /><Link href="/app" className="text-primary">← app</Link></div>;
  return (
    <div className="mx-auto max-w-7xl p-6">
      <PageHeader title={t("title")} actions={<Link href="/app" className="text-sm text-primary">← app</Link>} />
      <Tabs defaultValue="overview">
        <TabsList>{(["overview", "organizations", "prices", "rates", "deadLetters", "audit"] as const).map((k) => <TabsTrigger key={k} value={k}>{t(k)}</TabsTrigger>)}</TabsList>
        <TabsContent value="overview"><Json path="/admin/overview" /></TabsContent>
        <TabsContent value="organizations"><Orgs /></TabsContent>
        <TabsContent value="prices"><Prices /></TabsContent>
        <TabsContent value="rates"><Rates /></TabsContent>
        <TabsContent value="deadLetters"><DeadLetters /></TabsContent>
        <TabsContent value="audit"><Json path="/admin/audit-logs" /></TabsContent>
      </Tabs>
    </div>
  );
}

function Json({ path }: { path: string }) {
  const q = useQuery({ queryKey: [path], queryFn: () => api(path, { tenant: false }) });
  if (q.error) return <Alert tone="danger" title={errorMessage(q.error)} />;
  return <Card><pre className="max-h-[70vh] overflow-auto text-xs">{JSON.stringify(q.data, null, 2)}</pre></Card>;
}

function Orgs() {
  const t = useTranslations("admin");
  const [q, setQ] = useState("");
  const list = useQuery({ queryKey: ["admin-orgs", q], queryFn: () => api<Record<string, string>[]>(`/admin/organizations${q ? `?q=${encodeURIComponent(q)}` : ""}`, { tenant: false }) });
  return (
    <div className="space-y-3">
      <Input aria-label="search" placeholder="search" value={q} onChange={(e) => setQ(e.target.value)} className="max-w-sm" />
      <Table head={["Name", "Country", "Status", "Plan", "Subscription", ""]}>
        {list.data?.map((o) => (
          <tr key={o.id}><Td>{o.trade_name}</Td><Td>{o.country_code}</Td><Td><StatusBadge status={o.status} /></Td><Td>{o.plan ?? "—"}</Td><Td><StatusBadge status={o.subscription_status} /></Td>
            <Td><Button size="sm" variant={o.status === "ACTIVE" ? "danger" : "outline"} onClick={async () => {
              const reason = prompt("Reason (audited)"); if (!reason) return;
              await api(`/admin/organizations/${o.id}/suspension`, { tenant: false, body: { reason, suspend: o.status === "ACTIVE" } }); await list.refetch();
            }}>{o.status === "ACTIVE" ? t("suspend") : t("reinstate")}</Button></Td></tr>
        ))}
      </Table>
    </div>
  );
}

function Prices() {
  const list = useQuery({ queryKey: ["admin-prices"], queryFn: () => api<Record<string, unknown>[]>("/admin/prices", { tenant: false }) });
  const [f, setF] = useState({ planCode: "EMAIL_STARTER", currency: "BRL", unitAmountMinor: 0, taxBehavior: "EXCLUSIVE", stripePriceId: "price_" });
  const [err, setErr] = useState<string | null>(null);
  return (
    <div className="space-y-3">
      <Table head={["Plan", "Currency", "Amount (minor)", "Tax", "Stripe price", "Active"]}>
        {list.data?.map((p) => <tr key={String(p.id)}><Td>{String(p.code)}</Td><Td>{String(p.currency)}</Td><Td>{String(p.unit_amount_minor)}</Td><Td>{String(p.tax_behavior)}</Td><Td className="font-mono text-xs">{String(p.stripe_price_id ?? "— not linked")}</Td><Td>{String(p.active)}</Td></tr>)}
      </Table>
      <Card className="flex flex-wrap items-end gap-2">
        <Field label="Plan" htmlFor="pp"><Select id="pp" value={f.planCode} onChange={(e) => setF({ ...f, planCode: e.target.value })}>{["EMAIL_STARTER", "EMAIL_WHATSAPP", "COMPLETE_TRIO", "DAILY_CAMPAIGNS"].map((x) => <option key={x}>{x}</option>)}</Select></Field>
        <Field label="Currency" htmlFor="pcur"><Select id="pcur" value={f.currency} onChange={(e) => setF({ ...f, currency: e.target.value })}><option>USD</option><option>BRL</option><option>EUR</option></Select></Field>
        <Field label="Amount (minor)" htmlFor="pam"><Input id="pam" type="number" value={f.unitAmountMinor} onChange={(e) => setF({ ...f, unitAmountMinor: Number(e.target.value) })} /></Field>
        <Field label="Stripe price id" htmlFor="psp"><Input id="psp" value={f.stripePriceId} onChange={(e) => setF({ ...f, stripePriceId: e.target.value })} /></Field>
        <Button onClick={async () => { setErr(null); try { await api("/admin/prices", { tenant: false, body: f }); await list.refetch(); } catch (e) { setErr(errorMessage(e)); } }}>Set price</Button>
      </Card>
      {err && <Alert tone="danger" title={err} />}
    </div>
  );
}

function Rates() {
  const list = useQuery({ queryKey: ["admin-rates"], queryFn: () => api<Record<string, unknown>[]>("/admin/usage-rates", { tenant: false }) });
  const [f, setF] = useState({ channel: "WHATSAPP", countryCode: "BR", category: "MARKETING", currency: "BRL", unitPriceMinor: 0, providerCostMicros: 0 });
  const [err, setErr] = useState<string | null>(null);
  return (
    <div className="space-y-3">
      <Table head={["Channel", "Country", "Category", "Currency", "Unit price (minor)", "Provider cost (USD µ)"]}>
        {list.data?.map((r) => <tr key={String(r.id)}><Td>{String(r.channel)}</Td><Td>{String(r.country_code ?? "*")}</Td><Td>{String(r.message_category)}</Td><Td>{String(r.currency)}</Td><Td>{String(r.unit_price_minor)}</Td><Td>{String(r.provider_cost_micros ?? "—")}</Td></tr>)}
      </Table>
      <Card className="flex flex-wrap items-end gap-2">
        {(["channel", "countryCode", "category", "currency"] as const).map((k) => <Field key={k} label={k} htmlFor={k}><Input id={k} className="w-32" value={f[k]} onChange={(e) => setF({ ...f, [k]: e.target.value.toUpperCase() })} /></Field>)}
        <Field label="unit price (minor)" htmlFor="up"><Input id="up" type="number" value={f.unitPriceMinor} onChange={(e) => setF({ ...f, unitPriceMinor: Number(e.target.value) })} /></Field>
        <Field label="provider cost µUSD" htmlFor="pc"><Input id="pc" type="number" value={f.providerCostMicros} onChange={(e) => setF({ ...f, providerCostMicros: Number(e.target.value) })} /></Field>
        <Button onClick={async () => { setErr(null); try { await api("/admin/usage-rates", { tenant: false, body: f }); await list.refetch(); } catch (e) { setErr(errorMessage(e)); } }}>Set rate</Button>
      </Card>
      {err && <Alert tone="danger" title={err} />}
    </div>
  );
}

function DeadLetters() {
  const q = useQuery({ queryKey: ["dlq"], queryFn: () => api<{ jobs: Record<string, string>[]; automationInstances: Record<string, string>[]; outbox: Record<string, string>[] }>("/admin/dead-letters", { tenant: false }) });
  return (
    <div className="space-y-4">
      <Card><CardTitle>Jobs</CardTitle><Table head={["Type", "Attempts", "Error", ""]}>{q.data?.jobs.map((j) => <tr key={j.id}><Td>{j.job_type}</Td><Td>{j.attempts}</Td><Td className="text-xs">{j.last_error}</Td>
        <Td><Button size="sm" onClick={async () => { await api(`/admin/dead-letters/jobs/${j.id}/retry`, { tenant: false, method: "POST" }); await q.refetch(); }}>retry</Button></Td></tr>)}</Table></Card>
      <Card><CardTitle>Automation instances</CardTitle><Table head={["Instance", "Step", "Result", ""]}>{q.data?.automationInstances.map((j) => <tr key={j.id}><Td className="font-mono text-xs">{j.id}</Td><Td>{j.current_step_id}</Td><Td className="text-xs">{j.result}</Td>
        <Td><Button size="sm" onClick={async () => { await api(`/admin/dead-letters/automation-instances/${j.id}/retry`, { tenant: false, method: "POST" }); await q.refetch(); }}>retry</Button></Td></tr>)}</Table></Card>
      <Card><CardTitle>Outbox</CardTitle><Table head={["Event", "Attempts", "Error"]}>{q.data?.outbox.map((j) => <tr key={j.id}><Td>{j.event_type}</Td><Td>{j.attempts}</Td><Td className="text-xs">{j.last_error}</Td></tr>)}</Table></Card>
    </div>
  );
}

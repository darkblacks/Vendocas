"use client";
import { use, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useLocale, useTranslations } from "next-intl";
import { useRouter } from "@/i18n/navigation";
import { api, errorMessage } from "@/lib/api";
import { dateTime, money } from "@/lib/format";
import { useCan } from "@/lib/auth";
import { BASES, CHANNELS, PURPOSES, type Contact } from "@/features/types";
import { CustomerMetrics } from "@/features/crm/customer-metrics";
import { Alert, Badge, Button, Card, CardTitle, Field, Input, PageHeader, Select, Spinner, StatusBadge, Table, Td, Textarea } from "@/components/ui";

interface Consent { channel: string; purpose: string; status: string; legalBasis: string; grantedAt?: string; withdrawnAt?: string }
interface TimelineItem { kind: string; type: string; channel?: string; status?: string; summary?: string; occurredAt: string }

export default function ContactDetail({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const t = useTranslations();
  const locale = useLocale();
  const router = useRouter();
  const canManage = useCan("manage");
  const contact = useQuery({ queryKey: ["contact", id], queryFn: () => api<Contact>(`/contacts/${id}`) });
  const consents = useQuery({ queryKey: ["consents", id], queryFn: () => api<Consent[]>(`/contacts/${id}/consents`) });
  const timeline = useQuery({ queryKey: ["timeline", id], queryFn: () => api<TimelineItem[]>(`/contacts/${id}/timeline`) });
  if (contact.isLoading) return <Spinner />;
  if (!contact.data) return <Alert tone="danger" title={t("common.error")} />;
  const c = contact.data;
  return (
    <div>
      <PageHeader title={c.name || c.email || c.phoneE164 || "—"} description={`${c.email ?? ""} ${c.phoneE164 ?? ""}`}
        actions={canManage && (<>
          <Button variant="outline" onClick={async () => {
            const data = await api(`/privacy/contacts/${id}/export`);
            const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: "application/json" }));
            const a = document.createElement("a"); a.href = url; a.download = `contact-${id}.json`; a.click(); URL.revokeObjectURL(url);
          }}>{t("contacts.export")}</Button>
          <Button variant="danger" onClick={async () => { if (confirm(t("contacts.eraseConfirm"))) { await api(`/contacts/${id}`, { method: "DELETE" }); router.replace("/app/contacts"); } }}>{t("contacts.erase")}</Button>
        </>)} />
      <div className="grid gap-4 lg:grid-cols-2">
        <CustomerMetrics contactId={id} />
        <Card>
          <CardTitle>{t("common.details")}</CardTitle>
          <dl className="mt-3 space-y-2 text-sm">
            <Row k={t("common.status")} v={<StatusBadge status={c.status} />} />
            <Row k={t("common.country")} v={c.countryCode ?? "—"} />
            <Row k={t("contacts.lastPurchase")} v={dateTime(c.lastPurchaseAt, locale)} />
            <Row k="LTV" v={money(c.lifetimeValueMinor, c.lifetimeValueCurrency, locale)} />
            <Row k={t("contacts.tags")} v={<div className="flex flex-wrap justify-end gap-1">{c.tags.map((x) => <Badge key={x}>{x}</Badge>)}</div>} />
            <Row k="Source" v={c.source} />
          </dl>
        </Card>
        <Card className="lg:col-span-2">
          <CardTitle>{t("contacts.consents")}</CardTitle>
          <div className="mt-3">
            {consents.data && consents.data.length === 0 && <Alert tone="warning" title={t("automations.noConsentWarning")} />}
            {consents.data && consents.data.length > 0 && (
              <Table head={[t("common.channel"), t("common.purpose"), t("common.status"), t("contacts.legalBasis"), t("common.date")]}>
                {consents.data.map((x) => (
                  <tr key={x.channel + x.purpose}><Td>{x.channel}</Td><Td>{t(`automations.purposes.${x.purpose}`)}</Td><Td><StatusBadge status={x.status} /></Td>
                    <Td className="text-xs">{x.legalBasis}</Td><Td className="text-xs">{dateTime(x.grantedAt ?? x.withdrawnAt, locale)}</Td></tr>
                ))}
              </Table>
            )}
            <ConsentForm contactId={id} />
          </div>
        </Card>
      </div>
      <Card className="mt-4">
        <CardTitle>{t("contacts.timeline")}</CardTitle>
        <ol className="mt-3 space-y-2 text-sm">
          {timeline.data?.map((e, i) => (
            <li key={i} className="flex flex-wrap items-center gap-2 border-b border-border pb-2">
              <span className="w-40 text-xs text-muted-foreground">{dateTime(e.occurredAt, locale)}</span>
              <Badge>{e.kind}</Badge><span className="font-medium">{e.type}</span>
              {e.channel && <Badge tone="info">{e.channel}</Badge>}{e.status && <StatusBadge status={e.status} />}
              <span className="text-muted-foreground">{e.summary}</span>
            </li>
          ))}
        </ol>
      </Card>
    </div>
  );
}

function Row({ k, v }: { k: string; v: React.ReactNode }) {
  return <div className="flex justify-between gap-4"><dt className="text-muted-foreground">{k}</dt><dd className="text-right">{v}</dd></div>;
}

function ConsentForm({ contactId }: { contactId: string }) {
  const t = useTranslations();
  const qc = useQueryClient();
  const [f, setF] = useState({ channel: "EMAIL", purpose: "MARKETING", status: "GRANTED", legalBasis: "CONSENT", evidence: "" });
  const [error, setError] = useState<string | null>(null);
  return (
    <div className="mt-4 grid gap-3 rounded-lg border border-border p-3 sm:grid-cols-2">
      <Field label={t("common.channel")} htmlFor="cc"><Select id="cc" value={f.channel} onChange={(e) => setF({ ...f, channel: e.target.value })}>{[...CHANNELS, "VOICE"].map((c) => <option key={c}>{c}</option>)}</Select></Field>
      <Field label={t("common.purpose")} htmlFor="cpu"><Select id="cpu" value={f.purpose} onChange={(e) => setF({ ...f, purpose: e.target.value })}>{PURPOSES.map((p) => <option key={p} value={p}>{t(`automations.purposes.${p}`)}</option>)}</Select></Field>
      <Field label={t("common.status")} htmlFor="cs"><Select id="cs" value={f.status} onChange={(e) => setF({ ...f, status: e.target.value })}><option value="GRANTED">{t("contacts.grant")}</option><option value="WITHDRAWN">{t("contacts.withdraw")}</option></Select></Field>
      <Field label={t("contacts.legalBasis")} htmlFor="cl"><Select id="cl" value={f.legalBasis} onChange={(e) => setF({ ...f, legalBasis: e.target.value })}>{BASES.map((b) => <option key={b}>{b}</option>)}</Select></Field>
      <div className="sm:col-span-2"><Field label={t("contacts.evidence")} htmlFor="ce"><Textarea id="ce" value={f.evidence} onChange={(e) => setF({ ...f, evidence: e.target.value })} /></Field></div>
      {error && <div className="sm:col-span-2"><Alert tone="danger" title={error} /></div>}
      <div><Button size="sm" onClick={async () => {
        setError(null);
        try {
          await api(`/contacts/${contactId}/consents`, { method: "PUT", body: { ...f, source: "MANUAL" } });
          await qc.invalidateQueries({ queryKey: ["consents", contactId] });
          await qc.invalidateQueries({ queryKey: ["timeline", contactId] });
        } catch (e) { setError(errorMessage(e)); }
      }}>{t("common.save")}</Button></div>
    </div>
  );
}

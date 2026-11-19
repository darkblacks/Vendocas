"use client";
import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useLocale, useTranslations } from "next-intl";
import { api, errorMessage } from "@/lib/api";
import { dateTime } from "@/lib/format";
import { BASES, CHANNELS, PURPOSES } from "@/features/types";
import { Alert, Button, Card, CardTitle, Field, Input, PageHeader, Select, StatusBadge, Table, Td, Textarea } from "@/components/ui";

const FIELDS = ["ignore", "name", "email", "phone", "external_id", "country_code", "locale", "time_zone", "birth_date", "last_purchase_at",
  "last_interaction_at", "last_visit_at", "next_appointment_at", "lifetime_value", "tags"];

interface Upload { importId: string; headers: string[]; sample: string[][]; suggestedMapping: Record<string, string>; totalRows: number }
interface Preview { totalRows: number; validRows: number; invalidRows: number; duplicateInFile: number; matchingExisting: number; errors: { row: number; error: string }[] }
interface ImportRow { id: string; fileName: string; status: string; totalRows: number; createdRows: number; updatedRows: number; errorRows: number; createdAt: string }

export default function ImportPage() {
  const t = useTranslations();
  const locale = useLocale();
  const qc = useQueryClient();
  const [upload, setUpload] = useState<Upload | null>(null);
  const [mapping, setMapping] = useState<Record<string, string>>({});
  const [preview, setPreview] = useState<Preview | null>(null);
  const [declare, setDeclare] = useState(false);
  const [consent, setConsent] = useState({ channels: ["EMAIL"] as string[], purpose: "MARKETING", legalBasis: "CONSENT", evidence: "" });
  const [tags, setTags] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const history = useQuery({ queryKey: ["imports"], queryFn: () => api<ImportRow[]>("/contacts/imports"), refetchInterval: 5000 });

  async function onFile(file: File) {
    setError(null); setPreview(null); setBusy(true);
    try {
      const fd = new FormData(); fd.append("file", file);
      const u = await api<Upload>("/contacts/imports", { form: fd });
      setUpload(u); setMapping(u.suggestedMapping);
    } catch (e) { setError(errorMessage(e)); } finally { setBusy(false); }
  }
  async function doPreview() {
    if (!upload) return;
    setError(null); setBusy(true);
    try {
      setPreview(await api<Preview>(`/contacts/imports/${upload.importId}/mapping`, { method: "PUT", body: {
        mapping, tags: tags.split(",").map((s) => s.trim()).filter(Boolean), declaredConsent: declare ? consent : null } }));
    } catch (e) { setError(errorMessage(e)); } finally { setBusy(false); }
  }
  async function commit() {
    if (!upload) return;
    setBusy(true);
    try { await api(`/contacts/imports/${upload.importId}/commit`, { method: "POST" }); setUpload(null); setPreview(null); await qc.invalidateQueries({ queryKey: ["imports"] }); }
    catch (e) { setError(errorMessage(e)); } finally { setBusy(false); }
  }

  return (
    <div className="space-y-6">
      <PageHeader title={t("contacts.importTitle")} description={t("contacts.subtitle")} />
      <Card>
        <CardTitle>1. {t("contacts.upload")}</CardTitle>
        <input className="mt-3 block text-sm" type="file" accept=".csv,.xlsx" aria-label={t("contacts.upload")} onChange={(e) => e.target.files?.[0] && onFile(e.target.files[0])} />
        {busy && !upload && <p className="mt-2 text-sm text-muted-foreground">{t("common.loading")}</p>}
      </Card>
      {upload && (
        <Card>
          <CardTitle>2. {t("contacts.mapColumns")} ({upload.totalRows})</CardTitle>
          <div className="mt-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {upload.headers.map((h, i) => (
              <Field key={h + i} label={h} htmlFor={`m${i}`} hint={upload.sample.map((r) => r[i]).filter(Boolean).slice(0, 2).join(" · ")}>
                <Select id={`m${i}`} value={mapping[h] ?? "ignore"} onChange={(e) => setMapping({ ...mapping, [h]: e.target.value })}>
                  {FIELDS.map((f) => <option key={f} value={f}>{f === "ignore" ? t("contacts.ignore") : f}</option>)}
                </Select>
              </Field>
            ))}
          </div>
          <div className="mt-4"><Field label={t("contacts.tags")} htmlFor="itags"><Input id="itags" value={tags} onChange={(e) => setTags(e.target.value)} /></Field></div>
          <label className="mt-4 flex items-center gap-2 text-sm"><input type="checkbox" checked={declare} onChange={(e) => setDeclare(e.target.checked)} />{t("contacts.declaredConsent")}</label>
          {declare && (
            <div className="mt-3 grid gap-3 rounded-lg border border-warning/50 p-3 sm:grid-cols-3">
              <div className="sm:col-span-3"><Alert tone="warning" title={t("contacts.declaredConsentHelp")} /></div>
              <Field label={t("common.channel")} htmlFor="dch">
                <div id="dch" className="flex gap-3 text-sm">{CHANNELS.map((c) => (
                  <label key={c} className="flex items-center gap-1"><input type="checkbox" checked={consent.channels.includes(c)}
                    onChange={(e) => setConsent({ ...consent, channels: e.target.checked ? [...consent.channels, c] : consent.channels.filter((x) => x !== c) })} />{c}</label>))}</div>
              </Field>
              <Field label={t("common.purpose")} htmlFor="dpu"><Select id="dpu" value={consent.purpose} onChange={(e) => setConsent({ ...consent, purpose: e.target.value })}>{PURPOSES.map((p) => <option key={p} value={p}>{t(`automations.purposes.${p}`)}</option>)}</Select></Field>
              <Field label={t("contacts.legalBasis")} htmlFor="dlb"><Select id="dlb" value={consent.legalBasis} onChange={(e) => setConsent({ ...consent, legalBasis: e.target.value })}>{BASES.map((b) => <option key={b}>{b}</option>)}</Select></Field>
              <div className="sm:col-span-3"><Field label={t("contacts.evidence")} htmlFor="dev"><Textarea id="dev" value={consent.evidence} onChange={(e) => setConsent({ ...consent, evidence: e.target.value })} /></Field></div>
            </div>
          )}
          <Button className="mt-4" onClick={doPreview} loading={busy}>{t("contacts.preview")}</Button>
        </Card>
      )}
      {preview && (
        <Card>
          <CardTitle>3. {t("contacts.preview")}</CardTitle>
          <p className="mt-2 text-sm">{preview.validRows} {t("contacts.valid")} · {preview.invalidRows} {t("contacts.invalid")} · {preview.duplicateInFile} {t("contacts.duplicates")} · {preview.matchingExisting} {t("contacts.existing")}</p>
          {preview.errors.length > 0 && (
            <ul className="mt-2 max-h-48 overflow-y-auto text-xs text-danger">{preview.errors.map((e) => <li key={e.row}>#{e.row}: {e.error}</li>)}</ul>
          )}
          <Button className="mt-4" onClick={commit} loading={busy} disabled={preview.validRows === 0}>{t("contacts.commit")}</Button>
        </Card>
      )}
      {error && <Alert tone="danger" title={error} />}
      <Card>
        <CardTitle>{t("contacts.importsHistory")}</CardTitle>
        <div className="mt-3">
          <Table head={["File", t("common.status"), "Rows", "Created", "Updated", "Errors", t("common.date"), ""]}>
            {history.data?.map((r) => (
              <tr key={r.id}><Td>{r.fileName}</Td><Td><StatusBadge status={r.status} /></Td><Td>{r.totalRows}</Td><Td>{r.createdRows}</Td><Td>{r.updatedRows}</Td><Td>{r.errorRows}</Td>
                <Td className="text-xs">{dateTime(r.createdAt, locale)}</Td>
                <Td>{r.status === "COMPLETED" && <Button size="sm" variant="outline" onClick={async () => { await api(`/contacts/imports/${r.id}/rollback`, { method: "POST" }); await history.refetch(); }}>{t("contacts.rollback")}</Button>}</Td></tr>
            ))}
          </Table>
        </div>
      </Card>
    </div>
  );
}

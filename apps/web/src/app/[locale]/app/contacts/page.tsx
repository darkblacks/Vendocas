"use client";
import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useLocale, useTranslations } from "next-intl";
import { Link } from "@/i18n/navigation";
import { api, ApiError, errorMessage } from "@/lib/api";
import { date } from "@/lib/format";
import { useCan } from "@/lib/auth";
import type { Contact, Page } from "@/features/types";
import { Alert, Badge, Button, Dialog, DialogContent, DialogTrigger, EmptyState, Field, Input, PageHeader, Spinner, StatusBadge, Table, Td } from "@/components/ui";

export default function ContactsPage() {
  const t = useTranslations();
  const locale = useLocale();
  const canWrite = useCan("operate");
  const [q, setQ] = useState("");
  const [page, setPage] = useState(0);
  const query = useQuery({
    queryKey: ["contacts", q, page],
    queryFn: () => api<Page<Contact>>(`/contacts?page=${page}&size=25${q ? `&q=${encodeURIComponent(q)}` : ""}`),
  });
  return (
    <div>
      <PageHeader title={t("contacts.title")} description={t("contacts.subtitle")} actions={canWrite && (<>
        <Button variant="outline" asChild><Link href="/app/contacts/import">{t("contacts.import")}</Link></Button>
        <CreateContact />
      </>)} />
      <div className="mb-4 max-w-md"><Input placeholder={t("contacts.searchPlaceholder")} value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} aria-label={t("common.search")} /></div>
      {query.isLoading && <Spinner />}
      {query.data && query.data.items.length === 0 && <EmptyState title={t("common.noData")} />}
      {query.data && query.data.items.length > 0 && (
        <>
          <Table head={[t("common.name"), t("common.email"), t("common.phone"), t("contacts.tags"), t("contacts.lastPurchase"), t("common.status")]}>
            {query.data.items.map((c) => (
              <tr key={c.id} className="hover:bg-muted/50">
                <Td><Link href={`/app/contacts/${c.id}`} className="font-medium text-primary">{c.name || "—"}</Link></Td>
                <Td>{c.email ?? "—"}</Td>
                <Td className="tabular-nums">{c.phoneE164 ?? "—"}</Td>
                <Td><div className="flex flex-wrap gap-1">{c.tags.map((tg) => <Badge key={tg}>{tg}</Badge>)}</div></Td>
                <Td>{date(c.lastPurchaseAt, locale)}</Td>
                <Td><StatusBadge status={c.status} /></Td>
              </tr>
            ))}
          </Table>
          <div className="mt-3 flex items-center justify-between text-sm text-muted-foreground">
            <span>{query.data.totalItems}</span>
            <div className="flex gap-2">
              <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage(page - 1)}>{t("common.back")}</Button>
              <Button variant="outline" size="sm" disabled={page + 1 >= query.data.totalPages} onClick={() => setPage(page + 1)}>{t("common.next")}</Button>
            </div>
          </div>
        </>
      )}
    </div>
  );
}

function CreateContact() {
  const t = useTranslations();
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState({ name: "", email: "", phone: "", tags: "" });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  async function save() {
    setBusy(true); setError(null);
    try {
      await api("/contacts", { body: { name: form.name || null, email: form.email || null, phone: form.phone || null,
        tags: form.tags.split(",").map((s) => s.trim()).filter(Boolean) } });
      await qc.invalidateQueries({ queryKey: ["contacts"] });
      setOpen(false); setForm({ name: "", email: "", phone: "", tags: "" });
    } catch (e) { setError(e instanceof ApiError && e.fields.phone ? "Invalid phone number" : errorMessage(e)); } finally { setBusy(false); }
  }
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild><Button>{t("contacts.add")}</Button></DialogTrigger>
      <DialogContent title={t("contacts.add")} description={t("contacts.subtitle")}>
        <div className="space-y-3">
          <Field label={t("common.name")} htmlFor="cn"><Input id="cn" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></Field>
          <Field label={t("common.email")} htmlFor="ce"><Input id="ce" type="email" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} /></Field>
          <Field label={t("common.phone")} htmlFor="cp" hint="+55 11 98765-4321 / +1 212 555 0100"><Input id="cp" type="tel" value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} /></Field>
          <Field label={t("contacts.tags")} htmlFor="ct" hint={t("contacts.testRecipientHelp")}><Input id="ct" value={form.tags} onChange={(e) => setForm({ ...form, tags: e.target.value })} /></Field>
          {error && <Alert tone="danger" title={error} />}
          <Button onClick={save} loading={busy}>{t("common.save")}</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

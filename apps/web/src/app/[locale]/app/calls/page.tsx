"use client";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useLocale, useTranslations } from "next-intl";
import { api, errorMessage } from "@/lib/api";
import { dateTime } from "@/lib/format";
import { Alert, Button, Card, CardTitle, Input, PageHeader, Select, StatusBadge, Table, Tabs, TabsContent, TabsList, TabsTrigger, Td } from "@/components/ui";

interface NumberRow { id: string; e164: string; ownership: string; status: string; forwardTo?: string; testCallPassedAt?: string }
interface CallRow { id: string; direction: string; from: string; to: string; status: string; durationSeconds?: number; contactName?: string; test: boolean; createdAt: string }
interface TaskRow { id: string; contactName?: string; phone?: string; sourceType: string; status: string; dueAt?: string; notes?: string; outcome?: string }

export default function CallsPage() {
  const t = useTranslations("calls");
  const locale = useLocale();
  const numbers = useQuery({ queryKey: ["numbers"], queryFn: () => api<NumberRow[]>("/calls/numbers") });
  const missed = useQuery({ queryKey: ["calls", true], queryFn: () => api<CallRow[]>("/calls?missedOnly=true") });
  const all = useQuery({ queryKey: ["calls", false], queryFn: () => api<CallRow[]>("/calls") });
  const tasks = useQuery({ queryKey: ["tasks"], queryFn: () => api<TaskRow[]>("/calls/tasks?status=OPEN") });
  return (
    <div>
      <PageHeader title={t("title")} description={t("noRecording")} />
      <Tabs defaultValue="tasks">
        <TabsList><TabsTrigger value="tasks">{t("tasks")}</TabsTrigger><TabsTrigger value="missed">{t("missed")}</TabsTrigger><TabsTrigger value="all">{t("title")}</TabsTrigger><TabsTrigger value="numbers">{t("numbers")}</TabsTrigger></TabsList>
        <TabsContent value="tasks">
          <Table head={["Customer", "Phone", "Source", "Due", "Notes", ""]}>
            {tasks.data?.map((x) => (
              <tr key={x.id}><Td>{x.contactName ?? "—"}</Td><Td className="tabular-nums">{x.phone && <a className="text-primary" href={`tel:${x.phone}`}>{x.phone}</a>}</Td><Td>{x.sourceType}</Td>
                <Td className="text-xs">{dateTime(x.dueAt, locale)}</Td><Td className="text-xs">{x.notes}</Td>
                <Td className="whitespace-nowrap"><TaskDone id={x.id} onDone={() => tasks.refetch()} /></Td></tr>
            ))}
          </Table>
        </TabsContent>
        <TabsContent value="missed"><CallTable rows={missed.data} /></TabsContent>
        <TabsContent value="all"><CallTable rows={all.data} /></TabsContent>
        <TabsContent value="numbers">
          {numbers.data?.length === 0 && <Alert tone="info" title="Get a number in Channels → SMS / Voice to detect missed calls." />}
          <div className="grid gap-4 md:grid-cols-2">{numbers.data?.map((n) => <NumberCard key={n.id} n={n} onSaved={() => numbers.refetch()} />)}</div>
        </TabsContent>
      </Tabs>
    </div>
  );

  function CallTable({ rows }: { rows?: CallRow[] }) {
    return (
      <Table head={["When", "From", "To", "Status", "Duration", "Customer"]}>
        {rows?.map((c) => <tr key={c.id}><Td className="text-xs">{dateTime(c.createdAt, locale)}</Td><Td>{c.from}</Td><Td>{c.to}</Td><Td><StatusBadge status={c.status} />{c.test && " (test)"}</Td><Td>{c.durationSeconds ?? "—"}</Td><Td>{c.contactName ?? "—"}</Td></tr>)}
      </Table>
    );
  }
}

function TaskDone({ id, onDone }: { id: string; onDone: () => void }) {
  const t = useTranslations("calls");
  const [outcome, setOutcome] = useState("REACHED");
  return (
    <div className="flex gap-1">
      <Select className="h-8 w-36" aria-label={t("outcome")} value={outcome} onChange={(e) => setOutcome(e.target.value)}>{["REACHED", "NO_ANSWER", "WRONG_NUMBER", "SCHEDULED", "SOLD"].map((o) => <option key={o}>{o}</option>)}</Select>
      <Button size="sm" onClick={async () => { await api(`/calls/tasks/${id}`, { method: "PATCH", body: { status: "DONE", outcome } }); onDone(); }}>{t("markDone")}</Button>
    </div>
  );
}

function NumberCard({ n, onSaved }: { n: NumberRow; onSaved: () => void }) {
  const t = useTranslations("calls");
  const [mode, setMode] = useState(n.ownership === "FORWARDED" ? "CONDITIONAL_FORWARD_IN" : "FORWARD_TO_BUSINESS");
  const [phone, setPhone] = useState(n.forwardTo ?? "");
  const [error, setError] = useState<string | null>(null);
  return (
    <Card>
      <CardTitle>{n.e164} <StatusBadge status={n.status} /></CardTitle>
      <div className="mt-3 space-y-2 text-sm">
        <p className="font-medium">{t("routing")}</p>
        <label className="flex gap-2"><input type="radio" checked={mode === "FORWARD_TO_BUSINESS"} onChange={() => setMode("FORWARD_TO_BUSINESS")} />{t("forwardToBusiness")}</label>
        {mode === "FORWARD_TO_BUSINESS" && <Input aria-label={t("businessPhone")} placeholder={t("businessPhone")} value={phone} onChange={(e) => setPhone(e.target.value)} />}
        <label className="flex gap-2"><input type="radio" checked={mode === "CONDITIONAL_FORWARD_IN"} onChange={() => setMode("CONDITIONAL_FORWARD_IN")} />{t("conditional")}</label>
        {n.status === "TEST_PENDING" && <Alert tone="warning" title={t("testPending")} />}
        {error && <Alert tone="danger" title={error} />}
        <Button size="sm" onClick={async () => { setError(null); try { await api(`/calls/numbers/${n.id}/routing`, { method: "PUT", body: { mode, businessPhone: phone } }); onSaved(); } catch (e) { setError(errorMessage(e)); } }}>Save</Button>
      </div>
    </Card>
  );
}

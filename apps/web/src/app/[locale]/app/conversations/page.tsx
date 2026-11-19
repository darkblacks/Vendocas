"use client";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useLocale, useTranslations } from "next-intl";
import { api, errorMessage } from "@/lib/api";
import { dateTime } from "@/lib/format";
import { Alert, Badge, Button, Card, EmptyState, PageHeader, Select, StatusBadge, Textarea, cn } from "@/components/ui";

interface Conv { id: string; contactName?: string; contactAddress?: string; channel: string; status: string; lastMessageAt?: string; unreadCount: number; serviceWindowOpen: boolean }
interface Msg { id: string; direction: string; channel: string; status: string; statusReason?: string; subject?: string; body?: string; createdAt: string }

export default function ConversationsPage() {
  const t = useTranslations("conversations");
  const locale = useLocale();
  const [status, setStatus] = useState("OPEN");
  const [sel, setSel] = useState<Conv | null>(null);
  const [reply, setReply] = useState("");
  const [error, setError] = useState<string | null>(null);
  const list = useQuery({ queryKey: ["convs", status], queryFn: () => api<Conv[]>(`/conversations?status=${status}`), refetchInterval: 15000 });
  const msgs = useQuery({ queryKey: ["conv", sel?.id], enabled: !!sel, queryFn: () => api<Msg[]>(`/conversations/${sel!.id}/messages`), refetchInterval: 10000 });
  return (
    <div>
      <PageHeader title={t("title")} actions={<Select className="w-40" aria-label="status" value={status} onChange={(e) => setStatus(e.target.value)}><option>OPEN</option><option>PENDING</option><option>CLOSED</option></Select>} />
      <div className="grid gap-4 lg:grid-cols-3">
        <Card className="p-0">
          <ul className="divide-y divide-border">
            {list.data?.map((c) => (
              <li key={c.id}><button className={cn("w-full px-4 py-3 text-left hover:bg-muted", sel?.id === c.id && "bg-accent")} onClick={() => setSel(c)}>
                <div className="flex justify-between"><span className="font-medium">{c.contactName ?? c.contactAddress}</span>{c.unreadCount > 0 && <Badge tone="info">{c.unreadCount}</Badge>}</div>
                <div className="flex justify-between text-xs text-muted-foreground"><span>{c.channel}</span><span>{dateTime(c.lastMessageAt, locale)}</span></div>
              </button></li>
            ))}
          </ul>
          {list.data?.length === 0 && <div className="p-4"><EmptyState title={t("empty")} /></div>}
        </Card>
        <Card className="lg:col-span-2">
          {!sel && <EmptyState title={t("empty")} />}
          {sel && (
            <div className="flex h-full flex-col">
              <div className="flex flex-wrap items-center justify-between gap-2 border-b border-border pb-3">
                <div><p className="font-medium">{sel.contactName ?? sel.contactAddress}</p><p className="text-xs text-muted-foreground">{sel.channel} · {sel.channel === "WHATSAPP" ? (sel.serviceWindowOpen ? t("windowOpen") : t("windowClosed")) : ""}</p></div>
                <Button size="sm" variant="outline" onClick={async () => { await api(`/conversations/${sel.id}`, { method: "PATCH", body: { status: "CLOSED" } }); setSel(null); await list.refetch(); }}>{t("close")}</Button>
              </div>
              <ol className="my-3 max-h-[420px] flex-1 space-y-2 overflow-y-auto">
                {msgs.data?.map((m) => (
                  <li key={m.id} className={cn("max-w-[80%] rounded-lg px-3 py-2 text-sm", m.direction === "INBOUND" ? "bg-muted" : "ml-auto bg-accent")}>
                    {m.subject && <p className="font-medium">{m.subject}</p>}<p className="whitespace-pre-wrap">{m.body}</p>
                    <p className="mt-1 text-[10px] text-muted-foreground">{dateTime(m.createdAt, locale)} · <StatusBadge status={m.status} /> {m.statusReason}</p>
                  </li>
                ))}
              </ol>
              {error && <Alert tone="danger" title={error} />}
              <div className="flex gap-2">
                <Textarea aria-label={t("reply")} className="min-h-12" value={reply} onChange={(e) => setReply(e.target.value)} />
                <Button disabled={!reply.trim()} onClick={async () => {
                  setError(null);
                  try { const r = await api<{ status: string; reason?: string }>(`/conversations/${sel.id}/reply`, { body: { body: reply } });
                    if (r.status === "BLOCKED") setError(r.reason ?? "BLOCKED"); else setReply(""); await msgs.refetch(); }
                  catch (e) { setError(errorMessage(e)); }
                }}>{t("reply")}</Button>
              </div>
            </div>
          )}
        </Card>
      </div>
    </div>
  );
}

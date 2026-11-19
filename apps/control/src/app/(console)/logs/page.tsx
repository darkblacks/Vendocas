"use client";
import { usePoll } from "@/lib/use-poll";
import type { ApiStatus, LogRow } from "@/lib/types";
import { Alert, Badge, Card, Table, Td, Title, fmt, stateTone } from "@/components/ui";

export default function Logs() {
  const logs = usePoll<LogRow[]>("/api/logs", 30_000);
  const st = usePoll<ApiStatus>("/api/status", 60_000);
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold">Logs</h1>
      <Card><Title>Erros recentes da API de homologação</Title>
        {st.data && !st.data.reachable && <Alert tone="info">API indisponível ({st.data.reason}).</Alert>}
        <ul className="space-y-1 text-sm">{st.data?.data?.recentErrors.map((e, i) => <li key={i}><span className="text-muted-foreground">{fmt(e.at)}</span> {e.source}: {e.message}</li>)}
          {st.data?.reachable && st.data.data?.recentErrors.length === 0 && <li className="text-muted-foreground">Sem erros recentes.</li>}</ul></Card>
      <Card><Title>Operações do Control Center</Title>
        {logs.error && <Alert tone="bad">{logs.error}</Alert>}
        <Table head={["Quando", "Nível", "Categoria", "Mensagem"]}>
          {logs.data?.map((l, i) => <tr key={i}><Td className="whitespace-nowrap text-xs">{fmt(l.createdAt)}</Td><Td><Badge tone={stateTone(l.level)}>{l.level}</Badge></Td><Td>{l.category}</Td><Td>{l.message}</Td></tr>)}
        </Table></Card>
    </div>
  );
}

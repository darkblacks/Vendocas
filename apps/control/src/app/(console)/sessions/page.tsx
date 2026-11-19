"use client";
import { usePoll, duration } from "@/lib/use-poll";
import type { Session } from "@/lib/types";
import { Badge, Card, Table, Td, Title, fmt, stateTone } from "@/components/ui";

export default function Sessions() {
  const q = usePoll<Session[]>("/api/sessions", 30_000);
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold">Histórico de sessões</h1>
      <Card><Title>Sessões iniciadas pelo painel (limite máximo de 6 h)</Title>
        <Table head={["Início", "Por", "Estado", "Motivo", "Prazo", "Encerrada", "Duração", "Tentativas de parada"]}>
          {q.data?.map((s) => {
            const end = s.endedAt ? new Date(s.endedAt).getTime() : Date.now();
            return <tr key={s.id}><Td className="text-xs">{fmt(s.requestedAt)}</Td><Td>{s.startedBy ?? "—"}</Td><Td><Badge tone={stateTone(s.state)}>{s.state}</Badge></Td>
              <Td className="text-xs">{s.stopReason ?? "—"}{s.lastError ? ` · erro: ${s.lastError}` : ""}</Td><Td className="text-xs">{fmt(s.deadlineAt)}</Td><Td className="text-xs">{fmt(s.endedAt)}</Td>
              <Td className="font-mono text-xs">{duration(end - new Date(s.requestedAt).getTime())}</Td><Td>{s.stopAttempts}</Td></tr>;
          })}
        </Table></Card>
    </div>
  );
}

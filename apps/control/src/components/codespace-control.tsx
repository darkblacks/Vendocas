"use client";
import { useRef, useState } from "react";
import { Power, PowerOff } from "lucide-react";
import { call, message } from "@/lib/client";
import { duration, useNow } from "@/lib/use-poll";
import type { Overview } from "@/lib/types";
import { Alert, Badge, Button, Card, Title, fmt, stateTone } from "./ui";

/** Start/stop with one in-flight request at a time and an Idempotency-Key per click (double clicks cannot create two sessions). */
export function CodespaceControl({ ov, reload, canOperate }: { ov: Overview | null; reload: () => Promise<void>; canOperate: boolean }) {
  const now = useNow();
  const [busy, setBusy] = useState<"start" | "stop" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const inflight = useRef(false);
  if (!ov) return <Card><Title>Codespace</Title><p className="text-sm text-muted-foreground">Carregando estado real…</p></Card>;
  if (!ov.configured) return <Card><Title>Codespace</Title><Alert tone="warn">Nenhum Codespace selecionado. Cadastre o token GitHub em Credenciais e selecione o Codespace em Servidor.</Alert></Card>;
  const cs = ov.codespace;
  const s = ov.session;
  const deadline = s ? new Date(s.deadlineAt).getTime() : null;
  const since = s ? new Date(s.runningAt ?? s.requestedAt).getTime() : null;
  const running = cs?.state === "Available";
  const stopped = cs && ["Shutdown", "Archived"].includes(cs.state);

  async function act(kind: "start" | "stop") {
    if (inflight.current) return;
    inflight.current = true; setBusy(kind); setError(null); setInfo(null);
    try {
      const key = kind === "start" ? `start-${crypto.randomUUID()}` : undefined;
      const r = await call<{ alreadyRunning?: boolean; alreadyStopped?: boolean }>(`/api/codespaces/${kind}`, { method: "POST", headers: key ? { "Idempotency-Key": key } : {} });
      setInfo(kind === "start" ? (r.alreadyRunning ? "O Codespace já estava em execução; a sessão atual foi mantida/registrada." : "Inicialização solicitada ao GitHub. O estado será confirmado em instantes.")
        : r.alreadyStopped ? "O Codespace já estava parado." : "Desligamento solicitado ao GitHub. Aguardando confirmação.");
      await reload();
    } catch (e) { setError(message(e)); } finally { inflight.current = false; setBusy(null); }
  }

  return (
    <Card>
      <Title actions={cs && <Badge tone={stateTone(cs.state)}>{cs.state}</Badge>}>Codespace · {ov.settings.selectedCodespace}</Title>
      {ov.error && <Alert tone="bad">Não foi possível consultar o GitHub: {ov.error}. O estado exibido pode estar desatualizado.</Alert>}
      <dl className="grid grid-cols-2 gap-3 text-sm sm:grid-cols-4">
        <div><dt className="text-muted-foreground">Sessão</dt><dd className="font-medium">{s ? <Badge tone={stateTone(s.state)}>{s.state}</Badge> : "nenhuma"}</dd></div>
        <div><dt className="text-muted-foreground">Tempo de sessão</dt><dd className="font-mono tabular-nums">{since ? duration(now - since) : "—"}</dd></div>
        <div><dt className="text-muted-foreground">Desliga em</dt><dd className="font-mono tabular-nums">{deadline ? duration(deadline - now) : "—"}</dd></div>
        <div><dt className="text-muted-foreground">Desligamento previsto</dt><dd>{deadline ? fmt(s!.deadlineAt) : "—"}</dd></div>
      </dl>
      {s?.lastError && <p className="mt-2 text-xs text-danger">Último erro do agendador: {s.lastError} (tentativas: {s.stopAttempts})</p>}
      {cs?.idleTimeoutMinutes && <p className="mt-2 text-xs text-muted-foreground">O GitHub pode desligar por inatividade após {cs.idleTimeoutMinutes} min — antes do limite de {ov.settings.maxSessionHours} h. O painel mostra sempre o estado real.</p>}
      {canOperate && (
        <div className="mt-4 flex flex-wrap gap-2">
          <Button onClick={() => act("start")} loading={busy === "start"} disabled={!!busy || running || cs?.state === "Starting" || cs?.state === "ShuttingDown"}><Power className="h-4 w-4" />Ligar</Button>
          <Button variant="danger" onClick={() => act("stop")} loading={busy === "stop"} disabled={!!busy || !!stopped || cs?.state === "ShuttingDown"}><PowerOff className="h-4 w-4" />Desligar</Button>
        </div>
      )}
      {info && <div className="mt-3"><Alert tone="info">{info}</Alert></div>}
      {error && <div className="mt-3"><Alert tone="bad">{error}</Alert></div>}
    </Card>
  );
}

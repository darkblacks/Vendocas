"use client";
import { usePoll } from "@/lib/use-poll";
import type { ApiStatus, LogRow, Overview, Session } from "@/lib/types";
import { CodespaceControl } from "@/components/codespace-control";
import { Alert, Badge, Card, Title, fmt, stateTone } from "@/components/ui";
import { useMe } from "@/lib/use-me";

function Health({ label, up, detail }: { label: string; up: boolean | null; detail?: string }) {
  return (
    <div className="flex items-center justify-between rounded-lg border border-border px-3 py-2 text-sm">
      <span>{label}</span>
      <span className="flex items-center gap-2">{detail && <span className="text-xs text-muted-foreground">{detail}</span>}
        <Badge tone={up === null ? "neutral" : up ? "ok" : "bad"}>{up === null ? "desconhecido" : up ? "UP" : "DOWN"}</Badge></span>
    </div>
  );
}

export default function Dashboard() {
  const me = useMe();
  const ov = usePoll<Overview>("/api/codespaces", 15_000);
  const running = ov.data?.codespace?.state === "Available";
  const st = usePoll<ApiStatus>(running ? "/api/status" : null, 30_000);
  const sessions = usePoll<Session[]>("/api/sessions", 60_000);
  const logs = usePoll<LogRow[]>("/api/logs", 60_000);
  const deploys = usePoll<{ configured: boolean; mainCommit?: { sha: string; message: string; date: string; url: string } }>("/api/deploys", 120_000);
  const usage = usePoll<{ available: boolean; coreHours?: number; storageGbMonths?: number; reason?: string }>("/api/usage", 300_000);
  const settings = usePoll<{ links: { web: string | null }; selectedCodespace: string | null }>("/api/settings", 0);
  const a = st.data?.data;
  const failures = (logs.data ?? []).filter((l) => l.level !== "INFO").slice(0, 6);
  const apiUnknown = !running ? null : st.data ? st.data.reachable : null;
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold">Dashboard</h1>
      {ov.error && <Alert tone="bad">{ov.error}</Alert>}
      <CodespaceControl ov={ov.data} reload={ov.reload} canOperate={me?.role !== "VIEWER"} />
      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <Title actions={st.data && <span className="text-xs text-muted-foreground">verificado {fmt(st.data.checkedAt)}</span>}>Serviços de homologação</Title>
          {!running && <Alert tone="info">Codespace desligado: API, workers, PostgreSQL e Redis de homologação estão indisponíveis.</Alert>}
          {running && st.data && !st.data.reachable && <Alert tone="warn">API inacessível ({st.data.reason}). Pode estar iniciando, ou o token/URL da API não foi cadastrado em Credenciais.</Alert>}
          <div className="mt-3 space-y-2">
            <Health label="API Java" up={apiUnknown} detail={a?.status} />
            <Health label="Workers" up={a ? a.workers.enabled : running ? null : false} detail={a ? `jobs ${a.workers.jobsPending} · outbox ${a.workers.outboxPending} · DLQ ${a.workers.deadLetters}` : undefined} />
            <Health label="PostgreSQL" up={a ? a.database.up : running ? null : false} detail={a?.database.latencyMs != null ? `${a.database.latencyMs} ms · flyway v${a.database.flywayVersion ?? "?"}` : undefined} />
            <Health label="Redis" up={a ? a.redis.up : running ? null : false} />
          </div>
        </Card>
        <Card>
          <Title>Versão e ambientes</Title>
          <dl className="space-y-2 text-sm">
            <div className="flex justify-between gap-2"><dt className="text-muted-foreground">Último commit na main</dt>
              <dd className="text-right">{deploys.data?.mainCommit ? <a className="font-mono text-primary" href={deploys.data.mainCommit.url} target="_blank" rel="noreferrer">{deploys.data.mainCommit.sha.slice(0, 7)}</a> : "—"}</dd></div>
            <div className="flex justify-between gap-2"><dt className="text-muted-foreground">Commit em execução no Codespace</dt><dd className="font-mono">{a?.gitCommit?.slice(0, 7) ?? "—"}</dd></div>
            <div className="flex justify-between gap-2"><dt className="text-muted-foreground">API iniciada em</dt><dd>{fmt(a?.startedAt)}</dd></div>
            <div className="flex justify-between gap-2"><dt className="text-muted-foreground">Frontend Vendocas</dt>
              <dd>{settings.data?.links.web ? <a className="text-primary" href={settings.data.links.web} target="_blank" rel="noreferrer">abrir</a> : "não configurado"}</dd></div>
            <div className="flex justify-between gap-2"><dt className="text-muted-foreground">Codespace (VS Code web)</dt>
              <dd>{ov.data?.codespace?.webUrl ? <a className="text-primary" href={ov.data.codespace.webUrl} target="_blank" rel="noreferrer">abrir</a> : "—"}</dd></div>
            <div className="flex justify-between gap-2"><dt className="text-muted-foreground">Franquia Codespaces (mês)</dt>
              <dd>{usage.data?.available ? `${usage.data.coreHours?.toFixed(1)} core-h · ${usage.data.storageGbMonths?.toFixed(2)} GB-mês` : `indisponível pela API${usage.data?.reason ? ` (${usage.data.reason})` : ""}`}</dd></div>
          </dl>
        </Card>
      </div>
      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <Title>Inicializações recentes</Title>
          <ul className="space-y-1 text-sm">{(sessions.data ?? []).slice(0, 6).map((s) => (
            <li key={s.id} className="flex flex-wrap justify-between gap-2"><span>{fmt(s.requestedAt)} · {s.startedBy ?? "—"}</span><Badge tone={stateTone(s.state)}>{s.state}{s.stopReason ? ` · ${s.stopReason}` : ""}</Badge></li>))}
            {sessions.data?.length === 0 && <li className="text-muted-foreground">Nenhuma sessão registrada.</li>}</ul>
        </Card>
        <Card>
          <Title>Falhas recentes</Title>
          <ul className="space-y-1 text-sm">
            {failures.map((l, i) => <li key={i}><Badge tone={stateTone(l.level)}>{l.level}</Badge> <span className="text-muted-foreground">{fmt(l.createdAt)}</span> {l.message}</li>)}
            {(a?.recentErrors ?? []).slice(0, 4).map((e, i) => <li key={`api${i}`}><Badge tone="bad">API</Badge> <span className="text-muted-foreground">{fmt(e.at)}</span> {e.source}: {e.message}</li>)}
            {failures.length === 0 && !a?.recentErrors?.length && <li className="text-muted-foreground">Nenhuma falha registrada.</li>}
          </ul>
        </Card>
      </div>
    </div>
  );
}

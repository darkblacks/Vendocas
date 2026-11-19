"use client";
import { useState } from "react";
import { call, message } from "@/lib/client";
import { usePoll } from "@/lib/use-poll";
import { useMe } from "@/lib/use-me";
import type { Codespace, Overview } from "@/lib/types";
import { CodespaceControl } from "@/components/codespace-control";
import { Alert, Badge, Button, Card, Table, Td, Title, fmt, stateTone } from "@/components/ui";

const PORTS = [{ port: 8080, label: "API Vendocas (Spring Boot)" }, { port: 3000, label: "Frontend Vendocas (dev)" }];

export default function ServerPage() {
  const me = useMe();
  const ov = usePoll<Overview>("/api/codespaces", 15_000);
  const [list, setList] = useState<Codespace[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const cs = ov.data?.codespace;
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold">Servidor / Codespaces</h1>
      <CodespaceControl ov={ov.data} reload={ov.reload} canOperate={me?.role !== "VIEWER"} />
      {cs && (
        <Card>
          <Title>Detalhes</Title>
          <dl className="grid gap-2 text-sm sm:grid-cols-2">
            <div><dt className="text-muted-foreground">Repositório</dt><dd>{cs.repository}</dd></div>
            <div><dt className="text-muted-foreground">Branch</dt><dd>{cs.branch ?? "—"}</dd></div>
            <div><dt className="text-muted-foreground">Máquina</dt><dd>{cs.machine ?? "—"}</dd></div>
            <div><dt className="text-muted-foreground">Último uso</dt><dd>{fmt(cs.lastUsedAt)}</dd></div>
            <div><dt className="text-muted-foreground">Timeout de inatividade (GitHub)</dt><dd>{cs.idleTimeoutMinutes ? `${cs.idleTimeoutMinutes} min` : "—"}</dd></div>
            <div><dt className="text-muted-foreground">Retenção (exclusão automática)</dt><dd>{fmt(cs.retentionExpiresAt)}</dd></div>
          </dl>
          <h3 className="mt-4 text-sm font-semibold">Portas encaminhadas esperadas</h3>
          <p className="text-xs text-muted-foreground">URLs derivadas do nome do Codespace; a acessibilidade real é verificada pelo status da API no Dashboard.</p>
          <ul className="mt-2 space-y-1 text-sm">{PORTS.map((p) => <li key={p.port} className="break-all"><Badge>{p.port}</Badge> {p.label}: <span className="font-mono text-xs">https://{cs.name}-{p.port}.app.github.dev</span></li>)}</ul>
        </Card>
      )}
      {me?.role === "OWNER" && (
        <Card>
          <Title actions={<Button variant="outline" loading={busy} onClick={async () => { setBusy(true); setError(null); try { setList(await call<Codespace[]>("/api/codespaces/available")); } catch (e) { setError(message(e)); } finally { setBusy(false); } }}>Consultar Codespaces</Button>}>
            Selecionar Codespace do Vendocas</Title>
          <p className="text-sm text-muted-foreground">Somente Codespaces do repositório configurado no servidor ({ov.data?.settings.repository ?? "CONTROL_GITHUB_REPOSITORY não configurado"}) são listados.</p>
          {error && <div className="mt-2"><Alert tone="bad">{error}</Alert></div>}
          {list && (list.length === 0 ? <div className="mt-2"><Alert tone="warn">Nenhum Codespace encontrado para o repositório. Crie um (docs/codespaces-setup.md).</Alert></div> : (
            <div className="mt-3"><Table head={["Nome", "Estado", "Branch", "Último uso", ""]}>
              {list.map((c) => <tr key={c.name}><Td className="font-mono text-xs">{c.name}</Td><Td><Badge tone={stateTone(c.state)}>{c.state}</Badge></Td><Td>{c.branch}</Td><Td>{fmt(c.lastUsedAt)}</Td>
                <Td>{ov.data?.settings.selectedCodespace === c.name ? <Badge tone="ok">selecionado</Badge> :
                  <Button variant="outline" onClick={async () => { try { await call("/api/codespaces/select", { body: { name: c.name } }); await ov.reload(); } catch (e) { setError(message(e)); } }}>Selecionar</Button>}</Td></tr>)}
            </Table></div>))}
        </Card>
      )}
    </div>
  );
}

"use client";
import { useState } from "react";
import { call, message } from "@/lib/client";
import { usePoll } from "@/lib/use-poll";
import { useMe } from "@/lib/use-me";
import { Alert, Badge, Button, Card, Table, Td, Title, fmt, stateTone } from "@/components/ui";

interface A { id: number; actor: string; action: string; target: string | null; outcome: string; ip: string | null; createdAt: string }

export default function Security() {
  const me = useMe();
  const q = usePoll<A[]>(me && me.role !== "VIEWER" ? "/api/audit" : null, 30_000);
  const [msg, setMsg] = useState<{ tone: "ok" | "bad"; text: string } | null>(null);
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold">Segurança e auditoria</h1>
      {me?.role === "OWNER" && <Card><Title>Rotação da chave de instalação</Title>
        <p className="text-sm text-muted-foreground">1) Na Netlify, copie o valor atual de CONTROL_SETUP_KEY para CONTROL_SETUP_KEY_PREVIOUS e defina uma nova CONTROL_SETUP_KEY; publique. 2) Clique abaixo para recriptografar credenciais, segredos MFA e a connection string do banco. 3) Remova CONTROL_SETUP_KEY_PREVIOUS e publique.</p>
        <Button className="mt-3" variant="outline" onClick={async () => { setMsg(null); try { const r = await call<{ activeVersion: string; credentials: number; users: number }>("/api/vault/rotate", { method: "POST" });
          setMsg({ tone: "ok", text: `Recriptografado com ${r.activeVersion}: ${r.credentials} credenciais, ${r.users} segredos MFA.` }); } catch (e) { setMsg({ tone: "bad", text: message(e) }); } }}>Recriptografar com a chave ativa</Button>
        {msg && <div className="mt-3"><Alert tone={msg.tone}>{msg.text}</Alert></div>}</Card>}
      <Card><Title>Trilha de auditoria</Title>
        {me?.role === "VIEWER" && <Alert tone="info">Disponível para ADMIN e OWNER.</Alert>}
        {q.error && <Alert tone="bad">{q.error}</Alert>}
        <Table head={["Quando", "Ator", "Ação", "Alvo", "Resultado", "IP"]}>
          {q.data?.map((a) => <tr key={a.id}><Td className="whitespace-nowrap text-xs">{fmt(a.createdAt)}</Td><Td>{a.actor}</Td><Td className="font-mono text-xs">{a.action}</Td><Td className="text-xs">{a.target ?? "—"}</Td>
            <Td><Badge tone={stateTone(a.outcome)}>{a.outcome}</Badge></Td><Td className="text-xs">{a.ip ?? "—"}</Td></tr>)}
        </Table></Card>
    </div>
  );
}

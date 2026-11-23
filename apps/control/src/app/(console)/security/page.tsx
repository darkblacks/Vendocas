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
      <MyAccess />
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

interface Device { id: string; label: string | null; createdAt: string; lastUsedAt: string; expiresAt: string; current: boolean }

function deviceName(ua: string | null): string {
  if (!ua) return "Navegador";
  const browser = /Edg\//.test(ua) ? "Edge" : /Chrome\//.test(ua) ? "Chrome" : /Firefox\//.test(ua) ? "Firefox" : /Safari\//.test(ua) ? "Safari" : "Navegador";
  const os = /iPhone|iPad/.test(ua) ? "iPhone/iPad" : /Android/.test(ua) ? "Android" : /Windows/.test(ua) ? "Windows" : /Mac OS X/.test(ua) ? "macOS" : /Linux/.test(ua) ? "Linux" : "";
  return os ? `${browser} · ${os}` : browser;
}

/** The signed-in user's own access: trusted devices, recovery codes, sign out everywhere. */
function MyAccess() {
  const devices = usePoll<Device[]>("/api/auth/devices", 0);
  const me = usePoll<{ recoveryCodesLeft: number }>("/api/auth/me", 0);
  const [codes, setCodes] = useState<string[] | null>(null);
  const [msg, setMsg] = useState<{ tone: "ok" | "bad"; text: string } | null>(null);
  const act = async (fn: () => Promise<unknown>, ok: string) => { setMsg(null); try { await fn(); setMsg({ tone: "ok", text: ok }); } catch (e) { setMsg({ tone: "bad", text: message(e) }); } };
  return (
    <Card>
      <Title>Seu acesso</Title>
      <p className="mb-3 text-sm text-muted-foreground">Nos dispositivos confiáveis você entra só com e-mail e senha; o código do autenticador é pedido apenas em dispositivos novos. A sessão dura até 30 dias (7 dias sem uso encerram a sessão).</p>
      {msg && <div className="mb-3"><Alert tone={msg.tone}>{msg.text}</Alert></div>}
      <Table head={["Dispositivo", "Último uso", "Confiável até", ""]}>
        {devices.data?.map((d) => <tr key={d.id}><Td>{deviceName(d.label)}{d.current && <> <Badge tone="info">este</Badge></>}</Td><Td className="text-xs">{fmt(d.lastUsedAt)}</Td><Td className="text-xs">{fmt(d.expiresAt)}</Td>
          <Td><Button variant="ghost" onClick={() => act(() => call("/api/auth/devices", { body: { action: "revoke", id: d.id } }).then(() => devices.reload()), "Dispositivo esquecido: ali o código será pedido de novo.")}>Esquecer</Button></Td></tr>)}
        {devices.data?.length === 0 && <tr><Td className="text-muted-foreground">Nenhum dispositivo confiável.</Td><Td /><Td /><Td /></tr>}
      </Table>
      <div className="mt-3 flex flex-wrap gap-2">
        <Button variant="outline" onClick={() => act(async () => { setCodes((await call<{ recoveryCodes: string[] }>("/api/auth/recovery-codes", { method: "POST" })).recoveryCodes); await me.reload(); }, "Novos códigos gerados; os anteriores deixaram de valer.")}>
          Gerar novos códigos de recuperação{me.data ? ` (${me.data.recoveryCodesLeft} restantes)` : ""}</Button>
        <Button variant="danger" onClick={() => confirm("Encerrar todas as sessões e esquecer todos os dispositivos, inclusive este?") && act(async () => { await call("/api/auth/devices", { body: { action: "sign_out_everywhere" } }); window.location.href = "/login"; }, "")}>Sair de todos os dispositivos</Button>
      </div>
      {codes && <div className="mt-3 space-y-2">
        <Alert tone="warn">Guarde estes códigos em local seguro. Cada um funciona uma vez e eles não serão exibidos novamente.</Alert>
        <ul className="grid grid-cols-2 gap-2 font-mono text-sm sm:grid-cols-5">{codes.map((c) => <li key={c} className="rounded bg-muted px-2 py-1">{c}</li>)}</ul>
      </div>}
    </Card>
  );
}


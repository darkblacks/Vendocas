"use client";
import { useEffect, useState } from "react";
import { call, message } from "@/lib/client";
import { usePoll } from "@/lib/use-poll";
import { useMe } from "@/lib/use-me";
import { Alert, Badge, Button, Card, Field, Input, Table, Td, Title, fmt, stateTone } from "@/components/ui";

interface U { id: string; email: string; displayName: string; role: string; status: string; mfaEnabledAt: string | null }

export default function SettingsPage() {
  const me = useMe();
  const s = usePoll<{ maxSessionHours: number; repository: string | null; selectedCodespace: string | null; links: { web: string | null } }>("/api/settings", 0);
  const users = usePoll<U[]>(me && me.role !== "VIEWER" ? "/api/users" : null, 0);
  const [hours, setHours] = useState("6");
  const [msg, setMsg] = useState<{ tone: "ok" | "bad"; text: string } | null>(null);
  const [pw, setPw] = useState({ currentPassword: "", newPassword: "" });
  const [nu, setNu] = useState({ email: "", displayName: "", role: "ADMIN" });
  const [temp, setTemp] = useState<string | null>(null);
  useEffect(() => { if (s.data) setHours(String(s.data.maxSessionHours)); }, [s.data]);
  const act = async (fn: () => Promise<unknown>, ok: string) => { setMsg(null); try { await fn(); setMsg({ tone: "ok", text: ok }); } catch (e) { setMsg({ tone: "bad", text: message(e) }); } };
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-semibold">Configurações</h1>
      {msg && <Alert tone={msg.tone}>{msg.text}</Alert>}
      <Card><Title>Servidor</Title>
        <dl className="mb-3 space-y-1 text-sm"><div>Repositório controlado (servidor): <span className="font-mono">{s.data?.repository ?? "não configurado"}</span></div>
          <div>Codespace selecionado: <span className="font-mono">{s.data?.selectedCodespace ?? "—"}</span></div></dl>
        {me?.role === "OWNER" && <div className="flex flex-wrap items-end gap-2">
          <Field label="Limite de sessão (horas, máximo 6)" id="h"><Input id="h" type="number" min="0.5" max="6" step="0.5" value={hours} onChange={(e) => setHours(e.target.value)} className="w-32" /></Field>
          <Button onClick={() => act(() => call("/api/settings", { method: "PUT", body: { maxSessionHours: Number(hours) } }).then(() => s.reload()), "Limite atualizado (vale para novas sessões).")}>Salvar</Button></div>}
      </Card>
      <Card><Title>Alterar senha</Title>
        <div className="grid gap-2 sm:grid-cols-3">
          <Field label="Senha atual" id="cp"><Input id="cp" type="password" autoComplete="current-password" value={pw.currentPassword} onChange={(e) => setPw({ ...pw, currentPassword: e.target.value })} /></Field>
          <Field label="Nova senha (≥14)" id="np"><Input id="np" type="password" autoComplete="new-password" value={pw.newPassword} onChange={(e) => setPw({ ...pw, newPassword: e.target.value })} /></Field>
          <div className="flex items-end"><Button onClick={() => act(() => call("/api/auth/password", { body: pw }), "Senha alterada; outras sessões foram encerradas.")}>Alterar</Button></div>
        </div></Card>
      {users.data && <Card><Title>Administradores autorizados</Title>
        <Table head={["Nome", "E-mail", "Papel", "Estado", "MFA", ""]}>
          {users.data.map((u) => <tr key={u.id}><Td>{u.displayName}</Td><Td>{u.email}</Td><Td>{u.role}</Td><Td><Badge tone={stateTone(u.status)}>{u.status}</Badge></Td><Td>{u.mfaEnabledAt ? fmt(u.mfaEnabledAt) : "pendente"}</Td>
            <Td>{me?.role === "OWNER" && u.role !== "OWNER" && <div className="flex gap-1">
              <Button variant="outline" onClick={() => act(() => call(`/api/users/${u.id}`, { body: { action: u.status === "DISABLED" ? "enable" : "disable" } }).then(() => users.reload()), "Atualizado")}>{u.status === "DISABLED" ? "Reativar" : "Desativar"}</Button>
              <Button variant="ghost" onClick={() => act(() => call(`/api/users/${u.id}`, { body: { action: "revoke_sessions" } }), "Sessões encerradas")}>Encerrar sessões</Button>
              {u.status === "ACTIVE" && <Button variant="ghost" onClick={() => confirm(`Redefinir o MFA de ${u.email}? A pessoa terá de cadastrar um novo autenticador no próximo login.`) && act(() => call(`/api/users/${u.id}`, { body: { action: "reset_mfa" } }).then(() => users.reload()), "MFA redefinido")}>Redefinir MFA</Button>}</div>}</Td></tr>)}
        </Table>
        {me?.role === "OWNER" && <div className="mt-4 grid gap-2 sm:grid-cols-4">
          <Field label="E-mail" id="ue"><Input id="ue" type="email" value={nu.email} onChange={(e) => setNu({ ...nu, email: e.target.value })} /></Field>
          <Field label="Nome" id="un"><Input id="un" value={nu.displayName} onChange={(e) => setNu({ ...nu, displayName: e.target.value })} /></Field>
          <Field label="Papel" id="ur"><select id="ur" className="h-10 w-full rounded-lg border border-border bg-card px-2 text-sm" value={nu.role} onChange={(e) => setNu({ ...nu, role: e.target.value })}><option value="ADMIN">ADMIN</option><option value="VIEWER">VIEWER (somente leitura)</option></select></Field>
          <div className="flex items-end"><Button onClick={() => act(async () => { const r = await call<{ temporaryPassword: string }>("/api/users", { body: nu }); setTemp(r.temporaryPassword); await users.reload(); }, "Administrador criado")}>Autorizar</Button></div>
        </div>}
        {temp && <div className="mt-3"><Alert tone="warn">Senha temporária (exibida uma única vez; entregue por canal seguro — o MFA será exigido no primeiro acesso): <code className="break-all">{temp}</code></Alert></div>}
      </Card>}
    </div>
  );
}

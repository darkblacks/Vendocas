"use client";
import { useState } from "react";
import { call, message } from "@/lib/client";
import { usePoll } from "@/lib/use-poll";
import { Alert, Badge, Button, Card, Field, Input, Title, fmt, stateTone } from "@/components/ui";

interface FieldDef { key: string; label: string; secret: boolean; required: boolean; placeholder?: string; help?: string; defaultValue?: string; pattern?: string }
interface ProviderInfo { id: string; name: string; description: string; docsUrl: string; environments: ("TEST" | "LIVE")[]; fields: FieldDef[]; testable: boolean; revocableAtProvider: boolean; revokeHelp: string }
interface Cred { id: string; provider: string; environment: "TEST" | "LIVE"; status: string; masked: Record<string, string>; keyVersion: string; lastTestedAt: string | null;
  lastErrorCode: string | null; lastErrorMessage: string | null; updatedAt: string; updatedBy: string | null; version: number }

export default function CredentialsPage() {
  const q = usePoll<{ providers: ProviderInfo[]; credentials: Cred[] }>("/api/vault", 0);
  if (q.error) return <Alert tone="bad">{q.error}</Alert>;
  if (!q.data) return <p className="text-sm text-muted-foreground">Carregando…</p>;
  return (
    <div className="space-y-4">
      <div><h1 className="text-2xl font-semibold">Credenciais e Integrações</h1>
        <p className="text-sm text-muted-foreground">Os valores são criptografados (AES-256-GCM) no servidor e nunca são exibidos novamente após salvar. Salvar, substituir, desativar e revogar exigem confirmação de identidade.</p></div>
      {q.data.providers.map((p) => <ProviderCard key={p.id} p={p} creds={q.data!.credentials.filter((c) => c.provider === p.id)} reload={q.reload} />)}
    </div>
  );
}

function ProviderCard({ p, creds, reload }: { p: ProviderInfo; creds: Cred[]; reload: () => Promise<void> }) {
  const [env, setEnv] = useState<"TEST" | "LIVE">(p.environments[0]!);
  const [editing, setEditing] = useState(false);
  const [values, setValues] = useState<Record<string, string>>({});
  const [msg, setMsg] = useState<{ tone: "ok" | "bad" | "info"; text: string } | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [history, setHistory] = useState<{ action: string; environment: string; actor: string; createdAt: string }[] | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const live = creds.find((c) => c.environment === env && !["DISABLED", "REVOKED"].includes(c.status));

  async function act(name: string, fn: () => Promise<string>) {
    setBusy(name); setMsg(null);
    try { setMsg({ tone: "ok", text: await fn() }); await reload(); }
    catch (e) { setMsg({ tone: "bad", text: message(e) }); const f = (e as { body?: { fields?: Record<string, string> } }).body?.fields; if (f) setFieldErrors(f); }
    finally { setBusy(null); }
  }

  return (
    <Card>
      <Title actions={<div className="flex items-center gap-2">
        {p.environments.length > 1 && <select aria-label="Ambiente" className="h-9 rounded-lg border border-border bg-card px-2 text-sm" value={env} onChange={(e) => setEnv(e.target.value as "TEST" | "LIVE")}>
          {p.environments.map((e) => <option key={e} value={e}>{e === "TEST" ? "Teste / homologação" : "Produção"}</option>)}</select>}
        <Badge tone={stateTone(live?.status)}>{live?.status ?? "não cadastrada"}</Badge></div>}>{p.name}</Title>
      <p className="text-sm text-muted-foreground">{p.description} <a className="text-primary" href={p.docsUrl} target="_blank" rel="noreferrer">documentação</a></p>
      {live && (
        <dl className="mt-3 grid gap-2 text-sm sm:grid-cols-2">
          {Object.entries(live.masked).map(([k, v]) => <div key={k}><dt className="text-muted-foreground">{p.fields.find((f) => f.key === k)?.label ?? k}</dt><dd className="break-all font-mono text-xs">{v}</dd></div>)}
          <div><dt className="text-muted-foreground">Último teste</dt><dd>{fmt(live.lastTestedAt)}</dd></div>
          <div><dt className="text-muted-foreground">Atualizado</dt><dd>{fmt(live.updatedAt)} · {live.updatedBy ?? "—"} · v{live.version} · chave {live.keyVersion}</dd></div>
          {live.lastErrorCode && <div className="sm:col-span-2"><Alert tone="bad">Erro: {live.lastErrorCode} — {live.lastErrorMessage}</Alert></div>}
        </dl>
      )}
      {editing && (
        <form className="mt-4 grid gap-3 sm:grid-cols-2" autoComplete="off" onSubmit={(e) => { e.preventDefault(); void act("save", async () => {
          await call(`/api/vault/${p.id}`, { method: "PUT", body: { environment: env, values } }); setValues({}); setEditing(false); setFieldErrors({});
          return "Credencial salva e criptografada. Execute o teste de conexão."; }); }}>
          {p.fields.map((f) => (
            <Field key={f.key} id={`${p.id}-${f.key}`} label={`${f.label}${f.required ? "" : " (opcional)"}`} hint={f.help} error={fieldErrors[f.key]}>
              <Input id={`${p.id}-${f.key}`} type={f.secret ? "password" : "text"} autoComplete="off" spellCheck={false} placeholder={f.placeholder ?? f.defaultValue}
                value={values[f.key] ?? ""} onChange={(e) => setValues({ ...values, [f.key]: e.target.value })} />
            </Field>))}
          <div className="flex gap-2 sm:col-span-2"><Button type="submit" loading={busy === "save"}>Salvar {live ? "(substituir)" : ""}</Button><Button type="button" variant="outline" onClick={() => { setEditing(false); setValues({}); }}>Cancelar</Button></div>
        </form>
      )}
      <div className="mt-4 flex flex-wrap gap-2">
        {!editing && <Button variant="outline" onClick={() => setEditing(true)}>{live ? "Substituir" : "Cadastrar"}</Button>}
        {live && p.testable && <Button variant="outline" loading={busy === "test"} onClick={() => act("test", async () => {
          const r = await call<{ ok: boolean; code: string; message: string; account?: string }>(`/api/vault/${p.id}`, { body: { environment: env, action: "test" } });
          if (!r.ok) throw new Error(`${r.code}: ${r.message}`); return `Conexão OK: ${r.message}${r.account ? ` (${r.account})` : ""}`; })}>Testar conexão</Button>}
        {live && <Button variant="outline" loading={busy === "disable"} onClick={() => confirm("Desativar esta credencial?") && act("disable", async () => {
          await call(`/api/vault/${p.id}`, { body: { environment: env, action: "disable" } }); return "Credencial desativada."; })}>Desativar</Button>}
        {live && <Button variant="danger" loading={busy === "revoke"} onClick={() => confirm("Revogar e destruir o segredo armazenado?") && act("revoke", async () => {
          const r = await call<{ providerRevocation: { ok: boolean; message: string } | null; help: string }>(`/api/vault/${p.id}`, { body: { environment: env, action: "revoke" } });
          return `Segredo destruído localmente. ${r.providerRevocation ? `Provedor: ${r.providerRevocation.message}. ` : "Revogação no provedor não suportada via API. "}${r.help}`; })}>Revogar</Button>}
        <Button variant="ghost" onClick={async () => setHistory(history ? null : await call(`/api/vault/${p.id}`))}>{history ? "Ocultar histórico" : "Histórico"}</Button>
      </div>
      {msg && <div className="mt-3"><Alert tone={msg.tone}>{msg.text}</Alert></div>}
      {history && <ul className="mt-3 space-y-1 text-xs">{history.map((h, i) => <li key={i}>{fmt(h.createdAt)} · {h.environment} · {h.action} · {h.actor}</li>)}{history.length === 0 && <li>Sem histórico.</li>}</ul>}
    </Card>
  );
}

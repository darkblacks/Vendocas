"use client";
import { useCallback, useEffect, useState } from "react";
import { ApiError, call, message } from "@/lib/client";
import { Alert, Button, Card, Field, Input } from "@/components/ui";
import { EnrollMfa, type Enrollment } from "@/components/enroll";

interface Status {
  stage: "DATABASE" | "OWNER" | "DONE";
  completed: boolean;
  tokenConfigured: boolean;
  database: { configured: boolean; source: "env" | "store" | null; provider: string | null; serverVersion: string | null; locked: boolean; reachable: boolean | null; error: string | null };
}

const ERRORS: Record<string, string> = {
  BOOTSTRAP_TOKEN_INVALID: "Token de bootstrap inválido.",
  BOOTSTRAP_LOCKED: "A configuração inicial já foi concluída e está bloqueada.",
  BOOTSTRAP_NOT_CONFIGURED: "CONTROL_BOOTSTRAP_TOKEN não está configurado (mínimo de 32 caracteres) no ambiente da Netlify.",
  INVALID_CONNECTION_STRING: "Connection string inválida.",
  DB_AUTH_FAILED: "O banco recusou o usuário ou a senha. Copie a connection string novamente no painel do Neon.",
  DB_NOT_FOUND: "O banco indicado na connection string não existe.",
  DB_PERMISSION_DENIED: "O usuário não tem permissão para criar tabelas neste banco.",
  DB_HOST_NOT_FOUND: "Host do banco não encontrado.",
  DB_UNREACHABLE: "Não foi possível alcançar o servidor do banco (o projeto Neon pode estar suspenso; tente de novo).",
  DB_TLS_FAILED: "Falha na negociação TLS com o banco.",
  DB_VERSION_UNSUPPORTED: "É necessário PostgreSQL 14 ou superior.",
  DB_CONNECTION_FAILED: "Não foi possível conectar ao banco.",
  DATABASE_FROM_ENV: "O banco já está definido pela variável CONTROL_DATABASE_URL no ambiente.",
  CONFIG_STORE_UNAVAILABLE: "O armazenamento persistente de configuração (Netlify Blobs) não está disponível neste ambiente.",
  CONFIG_MISSING: "A chave mestra (CONTROL_MASTER_KEYS / CONTROL_ACTIVE_KEY_VERSION) não está configurada no ambiente.",
  RATE_LIMITED: "Muitas tentativas. Aguarde alguns minutos.",
};
const explain = (e: unknown) => (e instanceof ApiError ? `${ERRORS[e.code] ?? ""} ${ERRORS[e.code] && e.code !== "INVALID_CONNECTION_STRING" ? "" : e.message}`.trim() : message(e));

export default function Bootstrap() {
  const [status, setStatus] = useState<Status | null>(null);
  const [token, setToken] = useState("");
  const [conn, setConn] = useState("");
  const [connected, setConnected] = useState<string | null>(null);
  const [replaceDb, setReplaceDb] = useState(false);
  const [f, setF] = useState({ email: "", displayName: "", password: "" });
  const [enroll, setEnroll] = useState<Enrollment | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => call<Status>("/api/bootstrap").then(setStatus).catch((e) => setError(explain(e))), []);
  useEffect(() => { void load(); }, [load]);

  async function connect(e: React.FormEvent) {
    e.preventDefault(); setBusy(true); setError(null);
    try {
      const r = await call<{ provider: string; serverVersion: string; alreadyInitialized: boolean }>("/api/bootstrap/database", { body: { token, connectionString: conn } });
      setConn(""); // never keep the secret in the page longer than needed
      setReplaceDb(false);
      setConnected(`${r.provider === "PostgreSQL" ? "" : `${r.provider} · `}PostgreSQL ${r.serverVersion}${r.alreadyInitialized ? " — este banco já tinha uma configuração concluída" : ""}`);
      await load();
    } catch (err) { setError(explain(err)); } finally { setBusy(false); }
  }

  async function createOwner(e: React.FormEvent) {
    e.preventDefault(); setBusy(true); setError(null);
    try { setEnroll(await call<Enrollment>("/api/bootstrap", { body: { token, ...f } })); setToken(""); }
    catch (err) { setError(explain(err)); } finally { setBusy(false); }
  }

  const stage = enroll ? "MFA" : status?.stage;
  const showDbForm = status && !status.completed && (stage === "DATABASE" || (replaceDb && status.database.source === "store" && !status.database.locked));
  const steps = [["DATABASE", "Banco de dados"], ["OWNER", "Proprietário"], ["MFA", "MFA"]] as const;
  const order = ["DATABASE", "OWNER", "MFA", "DONE"];

  return (
    <main className="flex min-h-screen items-center justify-center p-4">
      <Card className="w-full max-w-md space-y-4">
        <h1 className="text-lg font-semibold">Configuração inicial</h1>
        {status && !status.completed && (
          <ol className="flex gap-2 text-xs" aria-label="Etapas">
            {steps.map(([k, label], i) => {
              const idx = order.indexOf(stage ?? "DATABASE");
              const st = i < idx ? "done" : i === idx ? "current" : "todo";
              return <li key={k} aria-current={st === "current" ? "step" : undefined}
                className={`flex-1 rounded border px-2 py-1 text-center ${st === "current" ? "border-primary font-medium" : st === "done" ? "border-success/40 text-muted-foreground" : "text-muted-foreground"}`}>{i + 1}. {label}</li>;
            })}
          </ol>
        )}
        {status?.completed && !enroll && <Alert tone="warn">A configuração inicial já foi concluída e está bloqueada. <a className="underline" href="/login">Entrar</a></Alert>}
        {status && !status.completed && !status.tokenConfigured && <Alert tone="bad">{ERRORS.BOOTSTRAP_NOT_CONFIGURED}</Alert>}
        {connected && <Alert tone="ok">Conectado e armazenado com segurança: {connected}</Alert>}
        {error && <Alert tone="bad">{error}</Alert>}

        {status && !status.completed && status.tokenConfigured && !enroll && (
          <Field label="Token de bootstrap" id="t" hint="Valor de CONTROL_BOOTSTRAP_TOKEN definido no ambiente da Netlify. Usado nas duas etapas.">
            <Input id="t" type="password" autoComplete="off" required value={token} onChange={(e) => setToken(e.target.value)} />
          </Field>
        )}

        {showDbForm && status.tokenConfigured && (
          <form className="space-y-3" onSubmit={connect}>
            <Field label="Connection string PostgreSQL (Neon)" id="conn"
              hint="Cole a URL completa do painel do Neon (Connect → Connection string), ex.: postgresql://usuario:senha@ep-…neon.tech/neondb?sslmode=require. Ela é testada, criptografada e nunca é exibida novamente.">
              <Input id="conn" type="password" autoComplete="off" spellCheck={false} required value={conn} onChange={(e) => setConn(e.target.value)} placeholder="postgresql://…" />
            </Field>
            <Button className="w-full" type="submit" loading={busy} disabled={!token || !conn}>Testar e conectar</Button>
            {replaceDb && <Button type="button" variant="ghost" className="w-full" onClick={() => setReplaceDb(false)}>Cancelar</Button>}
          </form>
        )}

        {stage === "OWNER" && !replaceDb && status && (
          <>
            <p className="text-sm text-muted-foreground">
              Banco: {status.database.source === "env" ? "definido por CONTROL_DATABASE_URL" : `${status.database.provider && status.database.provider !== "PostgreSQL" ? `${status.database.provider} · ` : ""}PostgreSQL ${status.database.serverVersion ?? ""}`}
              {status.database.reachable === false && " — inacessível no momento"}
              {status.database.source === "store" && !status.database.locked && <> · <button type="button" className="underline" onClick={() => { setReplaceDb(true); setConnected(null); }}>substituir connection string</button></>}
            </p>
            {status.database.reachable === false && <Alert tone="warn">O banco salvo não respondeu ({status.database.error ?? "erro"}). Verifique o projeto no Neon ou substitua a connection string.</Alert>}
            {status.tokenConfigured && (
              <form className="space-y-3" onSubmit={createOwner}>
                <Field label="E-mail do proprietário" id="e"><Input id="e" type="email" required value={f.email} onChange={(e) => setF({ ...f, email: e.target.value })} /></Field>
                <Field label="Nome" id="n"><Input id="n" required value={f.displayName} onChange={(e) => setF({ ...f, displayName: e.target.value })} /></Field>
                <Field label="Senha" id="p" hint="Mínimo de 14 caracteres."><Input id="p" type="password" autoComplete="new-password" required minLength={14} value={f.password} onChange={(e) => setF({ ...f, password: e.target.value })} /></Field>
                <Button className="w-full" type="submit" loading={busy} disabled={!token}>Criar proprietário</Button>
              </form>
            )}
          </>
        )}

        {enroll && <EnrollMfa enroll={enroll} />}
      </Card>
    </main>
  );
}

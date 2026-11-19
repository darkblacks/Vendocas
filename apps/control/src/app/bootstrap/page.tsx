"use client";
import { useState } from "react";
import { ApiError, call, message } from "@/lib/client";
import { Alert, Button, Card, Field, Input } from "@/components/ui";
import { EnrollMfa, type Enrollment } from "@/components/enroll";

type Stage = "AUTH" | "DATABASE" | "OWNER" | "MFA" | "DONE";
interface DbInfo { provider: string; serverVersion: string; canCreateTables?: boolean; alreadyInitialized?: boolean }

const ERRORS: Record<string, string> = {
  SETUP_NOT_CONFIGURED: "CONTROL_SETUP_KEY não está configurada na Netlify (mínimo de 32 caracteres). Cadastre-a e publique de novo.",
  SETUP_EMAIL_NOT_CONFIGURED: "CONTROL_SETUP_EMAIL não está configurado na Netlify.",
  SETUP_CREDENTIALS_INVALID: "E-mail ou chave de instalação incorretos.",
  SETUP_SESSION_REQUIRED: "Sua sessão de instalação terminou. Informe o e-mail e a chave novamente.",
  SETUP_SESSION_EXPIRED: "Sua sessão de instalação expirou (30 min). Informe o e-mail e a chave novamente.",
  INSTALLATION_COMPLETE: "Esta instalação já foi concluída. Entre com sua conta.",
  BOOTSTRAP_LOCKED: "A instalação já foi concluída e está bloqueada.",
  RATE_LIMITED: "Muitas tentativas. Aguarde alguns minutos.",
  CONFIG_STORE_UNAVAILABLE: "O armazenamento persistente (Netlify Blobs) não respondeu. Confirme que o site está publicado na Netlify e tente de novo.",
  CONFIG_STORE_TIMEOUT: "O armazenamento persistente (Netlify Blobs) demorou demais para responder. Tente de novo.",
  OPERATION_TIMEOUT: "A operação demorou demais. Tente novamente (um banco Neon suspenso pode levar alguns segundos para acordar).",
  GATEWAY_TIMEOUT: "O servidor não respondeu a tempo. Tente novamente.",
  INVALID_CONNECTION_STRING: "Connection string inválida.",
  DB_AUTH_FAILED: "O banco recusou o usuário ou a senha. Copie a connection string novamente no painel do Neon.",
  DB_NOT_FOUND: "O banco indicado na connection string não existe.",
  DB_PERMISSION_DENIED: "O usuário não tem permissão para criar tabelas neste banco.",
  DB_HOST_NOT_FOUND: "Host do banco não encontrado.",
  DB_UNREACHABLE: "Não foi possível alcançar o servidor do banco.",
  DB_TIMEOUT: "O banco demorou demais para responder.",
  DB_TLS_FAILED: "Falha na conexão segura (TLS) com o banco.",
  DB_ENDPOINT_NOT_FOUND: "O Neon não reconheceu o endpoint. Copie a connection string novamente.",
  DB_VERSION_UNSUPPORTED: "É necessário PostgreSQL 14 ou superior.",
  DB_CONNECTION_FAILED: "Não foi possível conectar ao banco.",
  DATABASE_FROM_ENV: "O banco já está definido pela variável CONTROL_DATABASE_URL no ambiente.",
  SETUP_KEY_MISMATCH: "Os dados salvos foram criptografados com outra CONTROL_SETUP_KEY.",
  CONFIG_DECRYPT_FAILED: "Não foi possível decifrar a configuração salva com a CONTROL_SETUP_KEY atual.",
};
function explain(e: unknown): string {
  if (!(e instanceof ApiError)) return message(e);
  const base = ERRORS[e.code];
  if (e.code === "INVALID_CONNECTION_STRING") return `${base} ${e.message}`;
  return base ?? e.message;
}

export default function Bootstrap() {
  // Renders immediately: no request is made until the owner submits the form.
  const [stage, setStage] = useState<Stage>("AUTH");
  const [email, setEmail] = useState("");
  const [key, setKey] = useState("");
  const [conn, setConn] = useState("");
  const [tested, setTested] = useState<DbInfo | null>(null);
  const [db, setDb] = useState<DbInfo | null>(null);
  const [owner, setOwner] = useState({ displayName: "", password: "", confirm: "" });
  const [enroll, setEnroll] = useState<Enrollment | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  async function run<T>(name: string, f: () => Promise<T>): Promise<T | undefined> {
    setBusy(name); setError(null); setNotice(null);
    try { return await f(); } catch (e) {
      if (e instanceof ApiError && (e.code === "SETUP_SESSION_REQUIRED" || e.code === "SETUP_SESSION_EXPIRED")) setStage("AUTH");
      if (e instanceof ApiError && e.code === "INSTALLATION_COMPLETE") setStage("DONE");
      setError(explain(e));
    } finally { setBusy(null); }
  }

  async function authenticate(e: React.FormEvent) {
    e.preventDefault();
    const r = await run("auth", () => call<{ stage: "DATABASE" | "OWNER"; database: { provider: string | null; serverVersion: string | null; reachable: boolean | null } }>("/api/bootstrap/session", { body: { email, key } }));
    if (!r) return;
    setKey(""); // the key is not kept in the page after use
    if (r.database.provider) setDb({ provider: r.database.provider, serverVersion: r.database.serverVersion ?? "" });
    if (r.stage === "DATABASE" && r.database.reachable === false) setNotice("O banco salvo anteriormente não respondeu. Cole uma connection string corrigida.");
    setStage(r.stage);
  }

  async function testDb() {
    const r = await run("test", () => call<DbInfo>("/api/bootstrap/database/test", { body: { connectionString: conn } }));
    if (r) setTested(r);
  }

  async function saveDb(e: React.FormEvent) {
    e.preventDefault();
    const r = await run("save", () => call<DbInfo & { stage: "OWNER" | "DONE" }>("/api/bootstrap/database", { body: { connectionString: conn } }));
    if (!r) return;
    setConn(""); setTested(null); setDb(r);
    setStage(r.stage);
    if (r.stage === "DONE") setNotice("Este banco já tinha uma instalação concluída; a configuração foi restaurada e bloqueada.");
  }

  async function createOwner(e: React.FormEvent) {
    e.preventDefault();
    if (owner.password !== owner.confirm) { setError("As senhas não coincidem."); return; }
    const r = await run("owner", () => call<Enrollment>("/api/bootstrap/owner", { body: { displayName: owner.displayName, password: owner.password } }));
    if (r) { setEnroll(r); setStage("MFA"); setOwner({ displayName: "", password: "", confirm: "" }); }
  }

  const label = (d: DbInfo) => `${d.provider === "PostgreSQL" ? "" : `${d.provider} · `}PostgreSQL ${d.serverVersion}`;
  const steps: [Stage, string][] = [["AUTH", "Instalação"], ["DATABASE", "Banco"], ["OWNER", "Proprietário"], ["MFA", "MFA"]];
  const idx = stage === "DONE" ? steps.length : steps.findIndex(([s]) => s === stage);

  return (
    <main className="flex min-h-screen items-center justify-center p-4">
      <Card className="w-full max-w-md space-y-4">
        <div>
          <h1 className="text-lg font-semibold">Instalação do Vendocas Control Center</h1>
          <p className="text-sm text-muted-foreground">Somente o proprietário, com a chave de instalação definida na Netlify.</p>
        </div>
        <ol className="grid grid-cols-4 gap-1 text-xs" aria-label="Etapas">
          {steps.map(([s, l], i) => (
            <li key={s} aria-current={i === idx ? "step" : undefined}
              className={`rounded border px-1 py-1 text-center ${i === idx ? "border-primary font-medium" : i < idx ? "border-success/40 text-muted-foreground" : "text-muted-foreground"}`}>{i + 1}. {l}</li>
          ))}
        </ol>
        {error && <Alert tone="bad">{error}</Alert>}
        {notice && <Alert tone="info">{notice}</Alert>}

        {stage === "AUTH" && (
          <form className="space-y-3" onSubmit={authenticate}>
            <Field label="E-mail" id="email"><Input id="email" type="email" autoComplete="username" required value={email} onChange={(e) => setEmail(e.target.value)} /></Field>
            <Field label="Chave de instalação" id="key" hint="O valor de CONTROL_SETUP_KEY cadastrado na Netlify.">
              <Input id="key" type="password" autoComplete="off" required value={key} onChange={(e) => setKey(e.target.value)} />
            </Field>
            <Button className="w-full" type="submit" loading={busy === "auth"}>Continuar</Button>
          </form>
        )}

        {stage === "DATABASE" && (
          <form className="space-y-3" onSubmit={saveDb}>
            <Field label="Connection String PostgreSQL" id="conn"
              hint="Cole a URL do painel do Neon (Connect → Connection string), ex.: postgresql://usuario:senha@ep-…neon.tech/neondb?sslmode=require. Ela é testada, criptografada e nunca é exibida novamente.">
              <Input id="conn" type="password" autoComplete="off" spellCheck={false} required value={conn}
                onChange={(e) => { setConn(e.target.value); setTested(null); }} placeholder="postgresql://…" />
            </Field>
            {tested && <Alert tone={tested.canCreateTables || tested.alreadyInitialized ? "ok" : "warn"}>
              Conexão OK: {label(tested)}.{" "}
              {tested.alreadyInitialized ? "Este banco já contém uma instalação concluída." : tested.canCreateTables ? "Permissão para criar tabelas confirmada." : "O usuário não pode criar tabelas neste banco."}
            </Alert>}
            <div className="grid grid-cols-2 gap-2">
              <Button type="button" variant="outline" loading={busy === "test"} disabled={!conn || !!busy} onClick={testDb}>Testar conexão</Button>
              <Button type="submit" loading={busy === "save"} disabled={!conn || !!busy}>Salvar e continuar</Button>
            </div>
          </form>
        )}

        {stage === "OWNER" && (
          <form className="space-y-3" onSubmit={createOwner}>
            {db && <Alert tone="ok">Banco conectado e salvo com criptografia: {label(db)}.</Alert>}
            <p className="text-sm">Conta do proprietário: <strong>{email}</strong></p>
            <Field label="Nome" id="n"><Input id="n" required value={owner.displayName} onChange={(e) => setOwner({ ...owner, displayName: e.target.value })} /></Field>
            <Field label="Senha" id="p" hint="Mínimo de 14 caracteres."><Input id="p" type="password" autoComplete="new-password" required minLength={14} value={owner.password} onChange={(e) => setOwner({ ...owner, password: e.target.value })} /></Field>
            <Field label="Confirme a senha" id="p2"><Input id="p2" type="password" autoComplete="new-password" required minLength={14} value={owner.confirm} onChange={(e) => setOwner({ ...owner, confirm: e.target.value })} /></Field>
            <Button className="w-full" type="submit" loading={busy === "owner"}>Criar conta e configurar MFA</Button>
          </form>
        )}

        {stage === "MFA" && enroll && <EnrollMfa enroll={enroll} />}

        {stage === "DONE" && <Alert tone="info">A instalação já está concluída. <a className="underline" href="/login">Entrar</a></Alert>}
      </Card>
    </main>
  );
}

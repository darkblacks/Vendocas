"use client";
import { useState } from "react";
import { ApiError, call, message, setCsrf } from "@/lib/client";
import { Alert, Button, Card, Field, Input } from "@/components/ui";
import { EnrollMfa, type Enrollment } from "@/components/enroll";

const ERRORS: Record<string, string> = {
  INVALID_CREDENTIALS: "E-mail ou senha incorretos.",
  RATE_LIMITED: "Muitas tentativas. Aguarde alguns minutos e tente de novo.",
  MFA_INVALID: "Código inválido ou expirado.",
  NETWORK: "Sem conexão com o servidor. Verifique a internet e tente de novo.",
};
const explain = (e: unknown) => (e instanceof ApiError && ERRORS[e.code]) || message(e);

/** Only same-site paths are accepted as the post-login destination (no open redirect). */
function destination(): string {
  const n = new URLSearchParams(window.location.search).get("next");
  return n && n.startsWith("/") && !n.startsWith("//") && !n.startsWith("/login") ? n : "/";
}

export default function Login() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [remember, setRemember] = useState(true);
  const [challenge, setChallenge] = useState<string | null>(null);
  const [enroll, setEnroll] = useState<Enrollment | null>(null);
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [setupPending, setSetupPending] = useState(false);
  const [forgot, setForgot] = useState(false);

  async function step1(e: React.FormEvent) {
    e.preventDefault(); setBusy(true); setError(null);
    try {
      const r = await call<{ step: "DONE" | "MFA" | "ENROLL"; challenge?: string; enroll?: Enrollment; csrfToken?: string }>("/api/auth/login", { body: { email, password } });
      if (r.step === "DONE") { setCsrf(r.csrfToken!); window.location.href = destination(); return; }
      if (r.step === "ENROLL") setEnroll(r.enroll!); else setChallenge(r.challenge!);
    } catch (err) {
      if (err instanceof ApiError && ["DATABASE_NOT_CONFIGURED", "SETUP_NOT_CONFIGURED"].includes(err.code)) setSetupPending(true);
      setError(explain(err));
    } finally { setBusy(false); }
  }
  async function step2(e: React.FormEvent) {
    e.preventDefault(); setBusy(true); setError(null);
    try { const r = await call<{ csrfToken: string }>("/api/auth/mfa", { body: { challenge, code, rememberDevice: remember } }); setCsrf(r.csrfToken); window.location.href = destination(); }
    catch (err) { setError(explain(err)); } finally { setBusy(false); }
  }

  return (
    <main className="flex min-h-screen items-center justify-center p-4">
      <Card className="w-full max-w-sm space-y-4">
        <div>{/* eslint-disable-next-line @next/next/no-img-element */}<img src="/brand/vendocas-simbolo-64.png" alt="" width={40} height={40} className="mb-3" /><h1 className="text-lg font-semibold">Vendocas Control Center</h1><p className="text-sm text-muted-foreground">Acesso restrito a administradores autorizados.</p></div>
        {setupPending && <Alert tone="info">A instalação ainda não foi concluída. <a className="underline" href="/bootstrap">Abrir a instalação</a></Alert>}
        {enroll ? <EnrollMfa enroll={enroll} /> : !challenge ? (
          <form onSubmit={step1} className="space-y-3">
            <Field label="E-mail" id="email"><Input id="email" type="email" autoComplete="username" required value={email} onChange={(e) => setEmail(e.target.value)} /></Field>
            <Field label="Senha" id="password"><Input id="password" type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} /></Field>
            {error && <Alert tone="bad">{error}</Alert>}
            <Button className="w-full" type="submit" loading={busy}>Entrar</Button>
            <button type="button" className="w-full text-center text-sm text-muted-foreground underline" onClick={() => setForgot(!forgot)}>Esqueci minha senha</button>
            {forgot && <Alert tone="info">
              Administradores: peça ao proprietário um link de redefinição (Configurações → Administradores). Proprietário: use a{" "}
              <a className="underline" href="/recover">recuperação com a chave de instalação</a>.
            </Alert>}
          </form>
        ) : (
          <form onSubmit={step2} className="space-y-3">
            <p className="text-sm text-muted-foreground">Primeiro acesso neste dispositivo: confirme com o código do app autenticador (ou um código de recuperação). Nos próximos acessos daqui, basta e-mail e senha.</p>
            <Field label="Código do autenticador ou de recuperação" id="code"><Input id="code" autoComplete="one-time-code" required autoFocus value={code} onChange={(e) => setCode(e.target.value.trim())} /></Field>
            <label className="flex items-start gap-2 text-sm">
              <input type="checkbox" className="mt-1" checked={remember} onChange={(e) => setRemember(e.target.checked)} />
              <span>Lembrar este dispositivo por 30 dias <span className="block text-xs text-muted-foreground">Desmarque em computadores compartilhados.</span></span>
            </label>
            {error && <Alert tone="bad">{error}</Alert>}
            <Button className="w-full" type="submit" loading={busy}>Confirmar</Button>
          </form>
        )}
      </Card>
    </main>
  );
}

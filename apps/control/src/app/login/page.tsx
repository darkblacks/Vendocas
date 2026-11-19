"use client";
import { useState } from "react";
import { ApiError, call, message, setCsrf } from "@/lib/client";
import { Alert, Button, Card, Field, Input } from "@/components/ui";
import { EnrollMfa, type Enrollment } from "@/components/enroll";

export default function Login() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [challenge, setChallenge] = useState<string | null>(null);
  const [enroll, setEnroll] = useState<Enrollment | null>(null);
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [setupPending, setSetupPending] = useState(false);

  async function step1(e: React.FormEvent) {
    e.preventDefault(); setBusy(true); setError(null);
    try {
      const r = await call<{ step: string; challenge?: string; enroll?: Enrollment }>("/api/auth/login", { body: { email, password } });
      if (r.step === "ENROLL") setEnroll(r.enroll!); else setChallenge(r.challenge!);
    } catch (err) {
      if (err instanceof ApiError && ["DATABASE_NOT_CONFIGURED", "SETUP_NOT_CONFIGURED"].includes(err.code)) setSetupPending(true);
      setError(message(err));
    } finally { setBusy(false); }
  }
  async function step2(e: React.FormEvent) {
    e.preventDefault(); setBusy(true); setError(null);
    try { const r = await call<{ csrfToken: string }>("/api/auth/mfa", { body: { challenge, code } }); setCsrf(r.csrfToken); window.location.href = "/"; }
    catch (err) { setError(message(err)); } finally { setBusy(false); }
  }

  return (
    <main className="flex min-h-screen items-center justify-center p-4">
      <Card className="w-full max-w-sm space-y-4">
        <div><h1 className="text-lg font-semibold">Vendocas Control Center</h1><p className="text-sm text-muted-foreground">Acesso restrito a administradores autorizados.</p></div>
        {setupPending && <Alert tone="info">A instalação ainda não foi concluída. <a className="underline" href="/bootstrap">Abrir a instalação</a></Alert>}
        {enroll ? <EnrollMfa enroll={enroll} /> : !challenge ? (
          <form onSubmit={step1} className="space-y-3">
            <Field label="E-mail" id="email"><Input id="email" type="email" autoComplete="username" required value={email} onChange={(e) => setEmail(e.target.value)} /></Field>
            <Field label="Senha" id="password"><Input id="password" type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} /></Field>
            {error && <Alert tone="bad">{error}</Alert>}
            <Button className="w-full" type="submit" loading={busy}>Entrar</Button>
          </form>
        ) : (
          <form onSubmit={step2} className="space-y-3">
            <Field label="Código do autenticador ou código de recuperação" id="code"><Input id="code" autoComplete="one-time-code" required value={code} onChange={(e) => setCode(e.target.value.trim())} /></Field>
            {error && <Alert tone="bad">{error}</Alert>}
            <Button className="w-full" type="submit" loading={busy}>Confirmar</Button>
          </form>
        )}
      </Card>
    </main>
  );
}

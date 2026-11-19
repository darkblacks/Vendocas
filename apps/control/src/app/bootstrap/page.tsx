"use client";
import { useEffect, useState } from "react";
import { call, message } from "@/lib/client";
import { Alert, Button, Card, Field, Input } from "@/components/ui";
import { EnrollMfa, type Enrollment } from "@/components/enroll";

export default function Bootstrap() {
  const [status, setStatus] = useState<{ completed: boolean; tokenConfigured: boolean } | null>(null);
  const [f, setF] = useState({ token: "", email: "", displayName: "", password: "" });
  const [enroll, setEnroll] = useState<Enrollment | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => { call<{ completed: boolean; tokenConfigured: boolean }>("/api/bootstrap").then(setStatus).catch((e) => setError(message(e))); }, []);
  return (
    <main className="flex min-h-screen items-center justify-center p-4">
      <Card className="w-full max-w-md space-y-4">
        <h1 className="text-lg font-semibold">Configuração inicial do proprietário</h1>
        {status?.completed && <Alert tone="warn">A configuração inicial já foi concluída e está bloqueada. <a className="underline" href="/login">Entrar</a></Alert>}
        {status && !status.completed && !status.tokenConfigured && <Alert tone="bad">CONTROL_BOOTSTRAP_TOKEN não está configurado no ambiente do servidor.</Alert>}
        {error && <Alert tone="bad">{error}</Alert>}
        {enroll ? <EnrollMfa enroll={enroll} /> : status && !status.completed && status.tokenConfigured && (
          <form className="space-y-3" onSubmit={async (e) => {
            e.preventDefault(); setBusy(true); setError(null);
            try { setEnroll(await call<Enrollment>("/api/bootstrap", { body: f })); } catch (err) { setError(message(err)); } finally { setBusy(false); }
          }}>
            <Field label="Token de bootstrap" id="t" hint="Valor de CONTROL_BOOTSTRAP_TOKEN (fornecido fora do navegador)."><Input id="t" type="password" required value={f.token} onChange={(e) => setF({ ...f, token: e.target.value })} /></Field>
            <Field label="E-mail do proprietário" id="e"><Input id="e" type="email" required value={f.email} onChange={(e) => setF({ ...f, email: e.target.value })} /></Field>
            <Field label="Nome" id="n"><Input id="n" required value={f.displayName} onChange={(e) => setF({ ...f, displayName: e.target.value })} /></Field>
            <Field label="Senha" id="p" hint="Mínimo de 14 caracteres."><Input id="p" type="password" autoComplete="new-password" required minLength={14} value={f.password} onChange={(e) => setF({ ...f, password: e.target.value })} /></Field>
            <Button className="w-full" type="submit" loading={busy}>Criar proprietário</Button>
          </form>
        )}
      </Card>
    </main>
  );
}

"use client";
import { useState } from "react";
import { ApiError, call, message } from "@/lib/client";
import { Alert, Button, Card, Field, Input } from "@/components/ui";
import { NewPasswordForm } from "@/components/new-password";

/**
 * Owner emergency access recovery with the installation credentials (CONTROL_SETUP_EMAIL + CONTROL_SETUP_KEY).
 * Resets password and MFA. Not used for the normal sign-in.
 */
export default function RecoverPage() {
  const [email, setEmail] = useState("");
  const [key, setKey] = useState("");
  const [token, setToken] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  return (
    <main className="flex min-h-screen items-center justify-center p-4">
      <Card className="w-full max-w-sm space-y-4">
        <div>
          <h1 className="text-lg font-semibold">Recuperar acesso do proprietário</h1>
          <p className="text-sm text-muted-foreground">Use apenas se perdeu a senha ou o app autenticador. A senha e o autenticador serão redefinidos e todos os dispositivos, desconectados.</p>
        </div>
        {token ? <NewPasswordForm token={token} /> : (
          <form className="space-y-3" onSubmit={async (e) => {
            e.preventDefault(); setBusy(true); setError(null);
            try { setToken((await call<{ token: string }>("/api/auth/recover", { body: { email, key } })).token); setKey(""); }
            catch (err) {
              setError(err instanceof ApiError && err.code === "SETUP_CREDENTIALS_INVALID" ? "E-mail ou chave de instalação incorretos."
                : err instanceof ApiError && err.code === "RATE_LIMITED" ? "Muitas tentativas. Aguarde alguns minutos." : message(err));
            } finally { setBusy(false); }
          }}>
            <Field label="E-mail do proprietário" id="e"><Input id="e" type="email" autoComplete="username" required value={email} onChange={(e) => setEmail(e.target.value)} /></Field>
            <Field label="Chave de instalação" id="k" hint="O valor de CONTROL_SETUP_KEY cadastrado na Netlify."><Input id="k" type="password" autoComplete="off" required value={key} onChange={(e) => setKey(e.target.value)} /></Field>
            {error && <Alert tone="bad">{error}</Alert>}
            <Button className="w-full" type="submit" loading={busy}>Continuar</Button>
            <a className="block text-center text-sm text-muted-foreground underline" href="/login">Voltar ao login</a>
          </form>
        )}
      </Card>
    </main>
  );
}

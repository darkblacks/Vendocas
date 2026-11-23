"use client";
import { useState } from "react";
import { ApiError, call, message } from "@/lib/client";
import { Alert, Button, Field, Input } from "./ui";

/** Final step of every recovery: sets a new password with a single-use token (never stored in the browser). */
export function NewPasswordForm({ token }: { token: string }) {
  const [pw, setPw] = useState("");
  const [pw2, setPw2] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<{ mfaReset: boolean } | null>(null);
  if (done) return (
    <Alert tone="ok">Senha redefinida. Todas as sessões e dispositivos confiáveis foram desconectados.{" "}
      {done.mfaReset ? "No próximo acesso você vai configurar o app autenticador de novo." : ""} <a className="underline" href="/login">Entrar</a></Alert>
  );
  return (
    <form className="space-y-3" onSubmit={async (e) => {
      e.preventDefault(); setError(null);
      if (pw !== pw2) { setError("As senhas não coincidem."); return; }
      setBusy(true);
      try { setDone(await call<{ mfaReset: boolean }>("/api/auth/reset", { body: { token, newPassword: pw } })); }
      catch (err) { setError(err instanceof ApiError && err.code === "RESET_TOKEN_INVALID" ? "Este link é inválido, já foi usado ou expirou." : message(err)); }
      finally { setBusy(false); }
    }}>
      <Field label="Nova senha" id="np" hint="Mínimo de 14 caracteres."><Input id="np" type="password" autoComplete="new-password" required minLength={14} value={pw} onChange={(e) => setPw(e.target.value)} /></Field>
      <Field label="Confirme a nova senha" id="np2"><Input id="np2" type="password" autoComplete="new-password" required minLength={14} value={pw2} onChange={(e) => setPw2(e.target.value)} /></Field>
      {error && <Alert tone="bad">{error}</Alert>}
      <Button className="w-full" type="submit" loading={busy}>Definir nova senha</Button>
    </form>
  );
}

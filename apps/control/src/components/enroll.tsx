"use client";
import { useState } from "react";
import { call, message, setCsrf } from "@/lib/client";
import { Alert, Button, Field, Input } from "./ui";

export interface Enrollment { enrollToken: string; secret: string; otpauthUri: string; qrDataUrl: string }

/** Mandatory MFA enrollment (TOTP). Shows the recovery codes exactly once. */
export function EnrollMfa({ enroll }: { enroll: Enrollment }) {
  const [code, setCode] = useState("");
  const [codes, setCodes] = useState<string[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  if (codes) {
    return (
      <div className="space-y-3">
        <Alert tone="warn">Guarde estes códigos de recuperação em local seguro. Cada um funciona uma única vez e não serão exibidos novamente.</Alert>
        <ul className="grid grid-cols-2 gap-2 font-mono text-sm">{codes.map((c) => <li key={c} className="rounded bg-muted px-2 py-1">{c}</li>)}</ul>
        <Button onClick={() => (window.location.href = "/")}>Continuar para o painel</Button>
      </div>
    );
  }
  return (
    <div className="space-y-3">
      <p className="text-sm">Configure a autenticação em dois fatores (obrigatória). Escaneie o QR code no app autenticador ou digite a chave.</p>
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img src={enroll.qrDataUrl} alt="QR code TOTP" className="mx-auto h-48 w-48 rounded bg-white p-2" />
      <code className="block break-all rounded bg-muted p-2 text-center text-xs">{enroll.secret}</code>
      <Field label="Código de 6 dígitos" id="code"><Input id="code" inputMode="numeric" autoComplete="one-time-code" maxLength={6} value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))} /></Field>
      {error && <Alert tone="bad">{error}</Alert>}
      <Button className="w-full" loading={busy} disabled={code.length !== 6} onClick={async () => {
        setBusy(true); setError(null);
        try { const r = await call<{ recoveryCodes: string[]; csrfToken: string }>("/api/auth/enroll", { body: { enrollToken: enroll.enrollToken, code } }); setCsrf(r.csrfToken); setCodes(r.recoveryCodes); }
        catch (e) { setError(message(e)); } finally { setBusy(false); }
      }}>Ativar MFA</Button>
    </div>
  );
}

"use client";
import { useEffect, useState } from "react";
import { Alert, Card } from "@/components/ui";
import { NewPasswordForm } from "@/components/new-password";

/** Opened from a one-time link: /reset#<token>. The fragment never reaches servers or logs; it is removed from the URL. */
export default function ResetPage() {
  const [token, setToken] = useState<string | null | undefined>(undefined);
  useEffect(() => {
    const t = window.location.hash.slice(1);
    setToken(t || null);
    if (t) history.replaceState(null, "", window.location.pathname);
  }, []);
  return (
    <main className="flex min-h-screen items-center justify-center p-4">
      <Card className="w-full max-w-sm space-y-4">
        <h1 className="text-lg font-semibold">Redefinir senha</h1>
        {token === null && <Alert tone="bad">Link incompleto. Peça um novo link de redefinição ao proprietário.</Alert>}
        {token && <NewPasswordForm token={token} />}
      </Card>
    </main>
  );
}

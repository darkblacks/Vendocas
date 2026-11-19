"use client";
import { useState } from "react";
import { useSearchParams } from "next/navigation";
import { useTranslations } from "next-intl";
import { Link } from "@/i18n/navigation";
import { api, errorMessage } from "@/lib/api";
import { Alert, Button, Field, Input } from "@/components/ui";

export default function Reset() {
  const t = useTranslations();
  const token = useSearchParams().get("token") ?? "";
  const [password, setPassword] = useState("");
  const [state, setState] = useState<"idle" | "done">("idle");
  const [error, setError] = useState<string | null>(null);
  if (state === "done") return <div className="space-y-3"><Alert tone="success" title={t("auth.resetDone")} /><Link href="/login" className="text-primary">{t("auth.loginTitle")}</Link></div>;
  return (
    <form className="space-y-4" onSubmit={async (e) => {
      e.preventDefault(); setError(null);
      try { await api("/auth/password/reset", { body: { token, password }, tenant: false }); setState("done"); } catch (err) { setError(errorMessage(err)); }
    }}>
      <h1 className="text-xl font-semibold">{t("auth.resetTitle")}</h1>
      <Field label={t("auth.password")} htmlFor="pw" hint={t("auth.passwordHint")}><Input id="pw" type="password" autoComplete="new-password" minLength={10} required value={password} onChange={(e) => setPassword(e.target.value)} /></Field>
      {error && <Alert tone="danger" title={error} />}
      <Button className="w-full" type="submit">{t("common.save")}</Button>
    </form>
  );
}

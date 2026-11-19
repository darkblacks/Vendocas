"use client";
import { useState } from "react";
import { useTranslations } from "next-intl";
import { api } from "@/lib/api";
import { Alert, Button, Field, Input } from "@/components/ui";

export default function Forgot() {
  const t = useTranslations();
  const [email, setEmail] = useState("");
  const [done, setDone] = useState(false);
  if (done) return <Alert tone="success" title={t("auth.forgotDone")} />;
  return (
    <form className="space-y-4" onSubmit={async (e) => { e.preventDefault(); await api("/auth/password/forgot", { body: { email }, tenant: false }).catch(() => undefined); setDone(true); }}>
      <h1 className="text-xl font-semibold">{t("auth.forgotTitle")}</h1>
      <Field label={t("common.email")} htmlFor="email"><Input id="email" type="email" required value={email} onChange={(e) => setEmail(e.target.value)} /></Field>
      <Button className="w-full" type="submit">{t("common.continue")}</Button>
    </form>
  );
}

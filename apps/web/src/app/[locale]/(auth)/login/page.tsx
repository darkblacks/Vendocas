"use client";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useTranslations } from "next-intl";
import { Link, useRouter } from "@/i18n/navigation";
import { api, ApiError, errorMessage } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { Alert, Button, Field, Input } from "@/components/ui";

const schema = z.object({ email: z.email(), password: z.string().min(1) });
type LoginResp = { accessToken?: string; mfaRequired: boolean; mfaToken?: string };

export default function LoginPage() {
  const t = useTranslations();
  const router = useRouter();
  const { signIn } = useAuth();
  const [error, setError] = useState<string | null>(null);
  const [unverified, setUnverified] = useState<string | null>(null);
  const [mfaToken, setMfaToken] = useState<string | null>(null);
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const form = useForm<z.infer<typeof schema>>({ resolver: zodResolver(schema) });

  async function finish(r: LoginResp) {
    if (r.mfaRequired && r.mfaToken) { setMfaToken(r.mfaToken); return; }
    if (r.accessToken) { await signIn(r.accessToken); router.replace("/app"); }
  }

  const onSubmit = form.handleSubmit(async (v) => {
    setError(null); setBusy(true);
    try { await finish(await api<LoginResp>("/auth/login", { body: v, tenant: false })); }
    catch (e) {
      if (e instanceof ApiError && e.code === "EMAIL_NOT_VERIFIED") setUnverified(v.email);
      setError(errorMessage(e));
    } finally { setBusy(false); }
  });

  async function submitMfa() {
    setError(null); setBusy(true);
    try { await finish(await api<LoginResp>("/auth/login/mfa", { body: { mfaToken, code }, tenant: false })); }
    catch (e) { setError(errorMessage(e)); } finally { setBusy(false); }
  }

  if (mfaToken) {
    return (
      <div className="space-y-4">
        <h1 className="text-xl font-semibold">{t("auth.mfaTitle")}</h1>
        <Field label={t("auth.mfaCode")} htmlFor="code">
          <Input id="code" inputMode="numeric" autoComplete="one-time-code" maxLength={6} value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))} />
        </Field>
        {error && <Alert tone="danger" title={error} />}
        <Button className="w-full" loading={busy} onClick={submitMfa} disabled={code.length !== 6}>{t("common.continue")}</Button>
      </div>
    );
  }

  return (
    <form onSubmit={onSubmit} className="space-y-4" noValidate>
      <h1 className="text-xl font-semibold">{t("auth.loginTitle")}</h1>
      <Field label={t("common.email")} htmlFor="email" error={form.formState.errors.email?.message}>
        <Input id="email" type="email" autoComplete="email" {...form.register("email")} />
      </Field>
      <Field label={t("auth.password")} htmlFor="password">
        <Input id="password" type="password" autoComplete="current-password" {...form.register("password")} />
      </Field>
      {error && <Alert tone="danger" title={error} />}
      {unverified && (
        <Button type="button" variant="outline" className="w-full" onClick={() => api("/auth/resend-verification", { body: { email: unverified }, tenant: false })}>
          {t("auth.resend")}
        </Button>
      )}
      <Button className="w-full" type="submit" loading={busy}>{t("auth.loginTitle")}</Button>
      <div className="flex justify-between text-sm">
        <Link href="/forgot-password" className="text-primary">{t("auth.forgot")}</Link>
        <Link href="/register" className="text-primary">{t("auth.noAccount")}</Link>
      </div>
    </form>
  );
}

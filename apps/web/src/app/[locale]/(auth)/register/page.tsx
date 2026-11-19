"use client";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useLocale, useTranslations } from "next-intl";
import { Link } from "@/i18n/navigation";
import { api, errorMessage } from "@/lib/api";
import { Alert, Button, Field, Input } from "@/components/ui";

const schema = z.object({ fullName: z.string().min(2).max(200), email: z.email(), password: z.string().min(10).max(128) });

export default function RegisterPage() {
  const t = useTranslations();
  const locale = useLocale();
  const [done, setDone] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const form = useForm<z.infer<typeof schema>>({ resolver: zodResolver(schema) });
  const onSubmit = form.handleSubmit(async (v) => {
    setError(null);
    try {
      const timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
      await api("/auth/register", { body: { ...v, locale, timeZone }, tenant: false });
      setDone(true);
    } catch (e) { setError(errorMessage(e)); }
  });
  if (done) return <Alert tone="success" title={t("auth.registerDone")} />;
  return (
    <form onSubmit={onSubmit} className="space-y-4" noValidate>
      <h1 className="text-xl font-semibold">{t("auth.registerTitle")}</h1>
      <Field label={t("auth.fullName")} htmlFor="fullName" error={form.formState.errors.fullName?.message}>
        <Input id="fullName" autoComplete="name" {...form.register("fullName")} />
      </Field>
      <Field label={t("common.email")} htmlFor="email" error={form.formState.errors.email?.message}>
        <Input id="email" type="email" autoComplete="email" {...form.register("email")} />
      </Field>
      <Field label={t("auth.password")} htmlFor="password" hint={t("auth.passwordHint")} error={form.formState.errors.password?.message}>
        <Input id="password" type="password" autoComplete="new-password" {...form.register("password")} />
      </Field>
      {error && <Alert tone="danger" title={error} />}
      <Button className="w-full" type="submit" loading={form.formState.isSubmitting}>{t("auth.registerTitle")}</Button>
      <p className="text-center text-sm"><Link href="/login" className="text-primary">{t("auth.haveAccount")}</Link></p>
    </form>
  );
}

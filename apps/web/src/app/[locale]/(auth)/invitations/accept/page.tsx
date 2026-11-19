"use client";
import { useState } from "react";
import { useSearchParams } from "next/navigation";
import { useTranslations } from "next-intl";
import { Link, useRouter } from "@/i18n/navigation";
import { api, errorMessage } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { Alert, Button } from "@/components/ui";

export default function AcceptInvitation() {
  const t = useTranslations("auth");
  const token = useSearchParams().get("token") ?? "";
  const { status, reload, setOrg } = useAuth();
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);
  if (status === "anonymous") return <div className="space-y-3"><Alert title={t("loginTitle")} /><Link href="/login" className="text-primary">{t("loginTitle")}</Link></div>;
  return (
    <div className="space-y-4">
      <h1 className="text-xl font-semibold">{t("acceptInvite")}</h1>
      {error && <Alert tone="danger" title={error} />}
      <Button onClick={async () => {
        try {
          const r = await api<{ organizationId: string }>("/members/invitations/accept", { body: { token }, tenant: false });
          await reload(); setOrg(r.organizationId); router.replace("/app");
        } catch (e) { setError(errorMessage(e)); }
      }}>{t("acceptInvite")}</Button>
    </div>
  );
}

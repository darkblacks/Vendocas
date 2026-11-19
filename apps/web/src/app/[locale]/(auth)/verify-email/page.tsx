"use client";
import { useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useTranslations } from "next-intl";
import { Link } from "@/i18n/navigation";
import { api } from "@/lib/api";
import { Alert, Spinner } from "@/components/ui";

export default function VerifyEmail() {
  const t = useTranslations("auth");
  const token = useSearchParams().get("token");
  const [state, setState] = useState<"pending" | "ok" | "fail">("pending");
  useEffect(() => {
    if (!token) { setState("fail"); return; }
    api("/auth/verify-email", { body: { token }, tenant: false }).then(() => setState("ok")).catch(() => setState("fail"));
  }, [token]);
  if (state === "pending") return <div className="flex items-center gap-2"><Spinner />{t("verifyTitle")}</div>;
  return state === "ok"
    ? <div className="space-y-4"><Alert tone="success" title={t("verified")} /><Link href="/login" className="text-primary">{t("loginTitle")}</Link></div>
    : <Alert tone="danger" title={t("verifyFailed")} />;
}

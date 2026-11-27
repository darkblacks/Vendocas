"use client";
import { useTranslations } from "next-intl";
import { cn } from "@/components/ui";

/** Mirrors the API's AlertFooter.text: store on top, "only an alert", then e-mail and phone on their own lines. */
export function AlertFooterPreview({ store, email, phone, className }: { store?: string | null; email?: string | null; phone?: string | null; className?: string }) {
  const t = useTranslations("journey.alertFooter");
  const s = store?.trim(), e = email?.trim(), p = phone?.trim();
  return (
    <div className={cn("text-xs leading-relaxed", className)}>
      <p>{s ? t("sentBy", { store: s }) : t("generic")}</p>
      {(e || p) && <p>{t("reach")}</p>}
      {e && <p>{t("email")}: {e}</p>}
      {p && <p>{t("phone")}: {p}</p>}
    </div>
  );
}

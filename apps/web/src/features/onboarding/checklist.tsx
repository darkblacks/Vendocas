"use client";
import { useQuery } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { CheckCircle2, Circle } from "lucide-react";
import { api } from "@/lib/api";
import { Link } from "@/i18n/navigation";
import { Card, CardTitle } from "@/components/ui";

interface Progress { organization: boolean; subscription: boolean; channel: boolean; contacts: boolean; automation: boolean; testSent: boolean; activated: boolean }

const STEPS: { key: keyof Progress; label: string; href: string }[] = [
  { key: "organization", label: "s1", href: "/app/settings" },
  { key: "subscription", label: "s2", href: "/app/billing" },
  { key: "channel", label: "s3", href: "/app/channels" },
  { key: "contacts", label: "s4", href: "/app/contacts" },
  { key: "automation", label: "s5", href: "/app/automations/new" },
  { key: "testSent", label: "s6", href: "/app/automations" },
  { key: "activated", label: "s7", href: "/app/automations" },
];

export function OnboardingChecklist({ compact = false }: { compact?: boolean }) {
  const t = useTranslations("onboarding");
  const q = useQuery({ queryKey: ["onboarding"], queryFn: () => api<Progress>("/reports/onboarding") });
  if (!q.data) return null;
  const done = STEPS.filter((s) => q.data[s.key]).length;
  if (compact && done === STEPS.length) return null;
  return (
    <Card className="mb-6">
      <div className="flex items-center justify-between">
        <CardTitle>{t("title")}</CardTitle>
        <span className="text-sm text-muted-foreground">{done}/{STEPS.length}</span>
      </div>
      <div className="mt-2 h-2 rounded-full bg-muted"><div className="h-2 rounded-full bg-primary" style={{ width: `${(done / STEPS.length) * 100}%` }} /></div>
      {!compact && <p className="mt-2 text-sm text-muted-foreground">{t("subtitle")}</p>}
      <ol className="mt-4 grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
        {STEPS.map((s) => (
          <li key={s.key}>
            <Link href={s.href} className="flex items-center gap-2 rounded-lg border border-border px-3 py-2 text-sm hover:bg-muted">
              {q.data[s.key] ? <CheckCircle2 className="h-4 w-4 text-success" aria-label={t("done")} /> : <Circle className="h-4 w-4 text-muted-foreground" aria-label={t("todo")} />}
              {t(s.label)}
            </Link>
          </li>
        ))}
      </ol>
    </Card>
  );
}

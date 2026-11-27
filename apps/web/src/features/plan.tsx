"use client";
import { useQuery } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { Gem } from "lucide-react";
import { api } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { Link } from "@/i18n/navigation";
import { cn } from "@/components/ui";
import type { Entitlements } from "@/features/markets";

/** Current plan features (shared cache with the shell and every screen). */
export function useEntitlements() {
  const { orgId } = useAuth();
  return useQuery({ queryKey: ["entitlements", orgId], enabled: !!orgId, queryFn: () => api<Entitlements>("/billing/entitlements") });
}

/** true/false once loaded; undefined while loading. Legacy plans (before Alerta/Ilimitado) are never locked. */
export function useHasFeature(feature: string): boolean | undefined {
  const e = useEntitlements();
  if (!e.data) return undefined;
  const f = e.data.features;
  if (!f.includes("ALERTS")) return true;
  return f.includes(feature);
}

/** Which plan unlocks a feature (shown on the prism badge). */
export const UNLOCKED_BY: Record<string, "ALERTA_VENDAS" | "ILIMITADO"> = {
  PIPELINE: "ALERTA_VENDAS", PERSONAS: "ALERTA_VENDAS", PRODUCT_CATALOG: "ALERTA_VENDAS", CAMPAIGNS: "ILIMITADO", INBOX: "ILIMITADO",
};

export function PlanBadge({ feature, className }: { feature: string; className?: string }) {
  const t = useTranslations("journey");
  return (
    <span className={cn("brand-gradient inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-semibold", className)}>
      <Gem className="h-3 w-3" aria-hidden />{t(`plans.${UNLOCKED_BY[feature] ?? "ALERTA_VENDAS"}`)}
    </span>
  );
}

/** Full-screen explanation for a locked screen: what it does, a preview and the way to unlock it (never a dead end). */
export function LockedScreen({ feature, title, text, children }: { feature: string; title: string; text: string; children?: React.ReactNode }) {
  const t = useTranslations("journey");
  return (
    <div className="space-y-6">
      <section className="brand-glow rounded-2xl p-6 sm:p-8">
        <PlanBadge feature={feature} />
        <h1 className="mt-3 text-2xl font-bold tracking-tight sm:text-3xl">{title}</h1>
        <p className="mt-2 max-w-2xl text-[#C9D2EA]">{text}</p>
        <Link href="/app/billing" className="brand-gradient mt-5 inline-flex min-h-11 items-center rounded-xl px-5 font-semibold">{t("seePlans")}</Link>
      </section>
      {children && (
        <div>
          <p className="mb-3 text-sm text-muted-foreground">{t("preview")}</p>
          <div aria-hidden className="pointer-events-none select-none opacity-60">{children}</div>
        </div>
      )}
    </div>
  );
}

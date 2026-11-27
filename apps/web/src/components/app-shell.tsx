"use client";
import * as React from "react";
import { useTranslations } from "next-intl";
import { useQuery } from "@tanstack/react-query";
import { BarChart3, Bell, CreditCard, Gem, Home, KanbanSquare, LineChart, MessageSquareHeart, Plug, Receipt, Settings, Shield, Users, UsersRound, Menu } from "lucide-react";
import { Link, usePathname, useRouter } from "@/i18n/navigation";
import { useAuth, useCan } from "@/lib/auth";
import { api } from "@/lib/api";
import { LocaleSwitcher, ThemeToggle } from "@/components/site-controls";
import { Button, Spinner, cn } from "@/components/ui";
import { Logo } from "@/components/brand";
import { useEntitlements } from "@/features/plan";

/**
 * Main menu = what the store wants to do today. `feature`: plan feature the screen needs; without it the item stays
 * visible with the prism (the screen explains it and how to unlock it — never hidden).
 */
const NAV: readonly { href: string; key: string; icon: typeof BarChart3; feature?: string }[] = [
  { href: "/app", key: "home", icon: Home },
  { href: "/app/contacts", key: "customers", icon: Users },
  { href: "/app/messages", key: "messages", icon: MessageSquareHeart },
  { href: "/app/sales", key: "pdv", icon: Receipt },
  { href: "/app/kanban", key: "kanban", icon: KanbanSquare, feature: "PIPELINE" },
  { href: "/app/insights", key: "insights", icon: LineChart },
  { href: "/app/personas", key: "personas", icon: UsersRound, feature: "PERSONAS" },
];
const NAV_BOTTOM: readonly { href: string; key: string; icon: typeof BarChart3 }[] = [
  { href: "/app/channels", key: "channels", icon: Plug },
  { href: "/app/billing", key: "billing", icon: CreditCard },
  { href: "/app/settings", key: "settings", icon: Settings },
];

/** Screens that stay reachable while the mandatory onboarding is not finished. */
const ONBOARDING_FREE = ["/app/onboarding", "/app/setup", "/app/channels", "/app/billing"];

export function AppShell({ children }: { children: React.ReactNode }) {
  const t = useTranslations();
  const { status, me, orgId, setOrg, signOut } = useAuth();
  const router = useRouter();
  const pathname = usePathname();
  const [open, setOpen] = React.useState(false);

  React.useEffect(() => {
    if (status === "anonymous") router.replace("/login");
    else if (status === "authenticated" && me && me.memberships.length === 0 && pathname !== "/app/setup") router.replace("/app/setup");
  }, [status, me, pathname, router]);

  const entitlements = useEntitlements();
  // Legacy plans (before the Alerta/Ilimitado catalogue) have no ALERTS flag: never lock their screens.
  const locked = (feature?: string) => {
    const f = entitlements.data?.features;
    return !!feature && !!f && f.includes("ALERTS") && !f.includes(feature);
  };
  const canManage = useCan("manage");
  const onboarding = useQuery({
    queryKey: ["onboarding-state", orgId], enabled: !!orgId,
    queryFn: () => api<{ completed: boolean }>("/onboarding"),
  });
  const onboardingPending = onboarding.data ? !onboarding.data.completed : undefined;
  React.useEffect(() => {
    if (onboardingPending && !ONBOARDING_FREE.some((p) => pathname.startsWith(p))) router.replace("/app/onboarding");
  }, [onboardingPending, pathname, router]);
  const notifications = useQuery({
    queryKey: ["notifications", orgId], enabled: !!orgId,
    queryFn: () => api<{ id: string; title: string; body: string; severity: string; read: boolean }[]>("/notifications?unread=true"),
    refetchInterval: 60_000,
  });

  if (status !== "authenticated" || !me) return <div className="flex min-h-screen items-center justify-center"><Spinner /></div>;
  if (me.memberships.length === 0) return <div className="mx-auto max-w-3xl p-6">{children}</div>;
  // The onboarding is a full-screen flow of its own (no menu until it is done).
  if (pathname.startsWith("/app/onboarding")) return <>{children}</>;
  if (onboardingPending === undefined && !ONBOARDING_FREE.some((p) => pathname.startsWith(p))) {
    return <div className="flex min-h-screen items-center justify-center"><Spinner /></div>;
  }
  if (onboardingPending && !canManage) {
    return <div className="mx-auto max-w-lg p-6 text-center"><Logo size={40} /><p className="mt-4">{t("journey.onboardingOwnerOnly")}</p></div>;
  }

  const item = (href: string, key: string, Icon: typeof BarChart3, feature?: string) => {
    const active = href === "/app" ? pathname === "/app" : pathname.startsWith(href);
    const isLocked = locked(feature);
    return (
      <Link key={href} href={href} onClick={() => setOpen(false)} aria-current={active ? "page" : undefined}
        className={cn("flex min-h-10 items-center gap-3 rounded-lg px-3 text-sm", active ? "bg-accent font-semibold text-accent-foreground" : "text-muted-foreground hover:bg-muted hover:text-foreground")}>
        <Icon className="h-4 w-4" aria-hidden />{t(`nav.${key}`)}
        {isLocked && <span className="ml-auto text-[var(--brand-orange)]" title={t("nav.lockedHint")}><Gem className="h-3.5 w-3.5" aria-hidden /><span className="sr-only">{t("nav.lockedHint")}</span></span>}
      </Link>
    );
  };
  const nav = (
    <nav className="flex h-[calc(100%-4rem)] flex-col gap-1 p-3" aria-label={t("nav.main")}>
      {NAV.map(({ href, key, icon, feature }) => item(href, key, icon, feature))}
      <div className="flex-1" />
      <div className="mt-4 border-t border-border pt-3">
        {NAV_BOTTOM.map(({ href, key, icon }) => item(href, key, icon))}
        {me.platformRole && (
          <Link href="/admin" className="flex min-h-10 items-center gap-3 rounded-lg px-3 text-sm text-muted-foreground hover:bg-muted"><Shield className="h-4 w-4" />{t("nav.admin")}</Link>
        )}
      </div>
    </nav>
  );

  return (
    <div className="flex min-h-screen">
      <aside className="hidden w-60 shrink-0 border-r border-border bg-card md:block">
        <div className="flex h-16 items-center px-5"><Logo size={28} /></div>
        {nav}
      </aside>
      {open && <div className="fixed inset-0 z-40 bg-black/40 md:hidden" onClick={() => setOpen(false)}><aside className="h-full w-64 bg-card" onClick={(e) => e.stopPropagation()}>{nav}</aside></div>}
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex h-16 items-center justify-between gap-3 border-b border-border px-4">
          <div className="flex items-center gap-2">
            <Button variant="ghost" size="icon" className="md:hidden" aria-label="Menu" onClick={() => setOpen(true)}><Menu className="h-5 w-5" /></Button>
            <label className="sr-only" htmlFor="org">Organization</label>
            <select id="org" className="max-w-[12rem] truncate rounded-lg border border-border bg-card px-2 py-1 text-sm" value={orgId ?? ""}
              onChange={(e) => { setOrg(e.target.value); window.location.reload(); }}>
              {me.memberships.map((m) => <option key={m.organizationId} value={m.organizationId}>{m.organizationName} · {m.role.toLowerCase()}</option>)}
            </select>
          </div>
          <div className="flex items-center gap-2">
            <Link href="/app/settings?tab=notifications" className="relative rounded-lg p-2 hover:bg-muted" aria-label="Notifications">
              <Bell className="h-4 w-4" />
              {!!notifications.data?.length && <span className="absolute right-1 top-1 h-2 w-2 rounded-full bg-danger" />}
            </Link>
            <LocaleSwitcher />
            <ThemeToggle />
            <Button variant="ghost" size="sm" onClick={async () => { await signOut(); router.replace("/"); }}>{t("common.signOut")}</Button>
          </div>
        </header>
        <main className="min-w-0 flex-1 p-4 sm:p-6">{children}</main>
      </div>
    </div>
  );
}

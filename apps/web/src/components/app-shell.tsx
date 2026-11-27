"use client";
import * as React from "react";
import { useTranslations } from "next-intl";
import { useQuery } from "@tanstack/react-query";
import { BarChart3, Bell, Bot, CreditCard, Lock, Megaphone, MessagesSquare, Phone, Plug, Receipt, Rocket, Settings, Shield, Users, Menu } from "lucide-react";
import { Link, usePathname, useRouter } from "@/i18n/navigation";
import { useAuth } from "@/lib/auth";
import { api } from "@/lib/api";
import { LocaleSwitcher, ThemeToggle } from "@/components/site-controls";
import { Button, Spinner, cn } from "@/components/ui";

/** `feature`: plan feature the screen needs; without it the item stays visible with a lock (never hidden). */
const NAV: readonly { href: string; key: string; icon: typeof BarChart3; feature?: string }[] = [
  { href: "/app", key: "dashboard", icon: BarChart3 },
  { href: "/app/onboarding", key: "onboarding", icon: Rocket },
  { href: "/app/contacts", key: "customers", icon: Users },
  { href: "/app/automations", key: "alerts", icon: Bot },
  { href: "/app/sales", key: "sales", icon: Receipt, feature: "SALES_REGISTRY" },
  { href: "/app/campaigns", key: "campaigns", icon: Megaphone, feature: "CAMPAIGNS" },
  { href: "/app/conversations", key: "conversations", icon: MessagesSquare, feature: "INBOX" },
  { href: "/app/calls", key: "calls", icon: Phone },
  { href: "/app/channels", key: "channels", icon: Plug },
  { href: "/app/billing", key: "billing", icon: CreditCard },
  { href: "/app/settings", key: "settings", icon: Settings },
];

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

  const entitlements = useQuery({
    queryKey: ["entitlements"], enabled: !!orgId,
    queryFn: () => api<{ planCode?: string | null; features: string[] }>("/billing/entitlements"),
  });
  // Legacy plans (before the Alerta/Ilimitado catalogue) have no ALERTS flag: never lock their screens.
  const locked = (feature?: string) => {
    const f = entitlements.data?.features;
    return !!feature && !!f && f.includes("ALERTS") && !f.includes(feature);
  };
  const notifications = useQuery({
    queryKey: ["notifications", orgId], enabled: !!orgId,
    queryFn: () => api<{ id: string; title: string; body: string; severity: string; read: boolean }[]>("/notifications?unread=true"),
    refetchInterval: 60_000,
  });

  if (status !== "authenticated" || !me) return <div className="flex min-h-screen items-center justify-center"><Spinner /></div>;
  if (me.memberships.length === 0) return <div className="mx-auto max-w-3xl p-6">{children}</div>;

  const nav = (
    <nav className="flex flex-col gap-1 p-3">
      {NAV.map(({ href, key, icon: Icon, feature }) => {
        const active = href === "/app" ? pathname === "/app" : pathname.startsWith(href);
        const isLocked = locked(feature);
        return (
          <Link key={href} href={isLocked ? "/app/billing" : href} onClick={() => setOpen(false)} title={isLocked ? t("nav.lockedHint") : undefined}
            className={cn("flex items-center gap-3 rounded-lg px-3 py-2 text-sm", active ? "bg-accent font-medium text-accent-foreground" : "text-muted-foreground hover:bg-muted")}>
            <Icon className="h-4 w-4" aria-hidden />{t(`nav.${key}`)}
            {isLocked && <span className="ml-auto inline-flex items-center gap-1 text-xs"><Lock className="h-3 w-3" aria-hidden /><span className="sr-only">{t("nav.lockedHint")}</span></span>}
          </Link>
        );
      })}
      {me.platformRole && (
        <Link href="/admin" className="mt-4 flex items-center gap-3 rounded-lg px-3 py-2 text-sm text-muted-foreground hover:bg-muted"><Shield className="h-4 w-4" />{t("nav.admin")}</Link>
      )}
    </nav>
  );

  return (
    <div className="flex min-h-screen">
      <aside className="hidden w-60 shrink-0 border-r border-border bg-card md:block">
        <div className="flex h-16 items-center px-5 text-lg font-bold">Vendocas</div>
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

"use client";
import { useQuery } from "@tanstack/react-query";
import { useLocale, useTranslations } from "next-intl";
import { CheckCircle2, Circle, KanbanSquare, LineChart, MessageSquareHeart, Receipt, UserPlus, UsersRound } from "lucide-react";
import { api } from "@/lib/api";
import { money } from "@/lib/format";
import { useAuth } from "@/lib/auth";
import { Link } from "@/i18n/navigation";
import { Card, cn } from "@/components/ui";
import { QuotaBars } from "@/features/crm/quota-bars";
import { PlanBadge, useEntitlements } from "@/features/plan";
import { MARKET_CURRENCY, type UsageView } from "@/features/markets";
import { PRESET_INACTIVE } from "@/features/messages-presets";

interface Summary { currency?: string | null; recoveredMinor: number; recoveredSales: number; stages: Record<string, number>; activeCustomers: number }
interface Onb { storeName?: string | null; whatsappChannelStatus?: string | null; emailChannelStatus?: string | null }
interface Automation { id: string; description?: string | null; status: string }

/** Início: thanks the store, asks "what do you want to do today?" and shows the first steps and a few numbers. */
export default function HomePage() {
  const t = useTranslations("journey.home");
  const locale = useLocale();
  const { me, orgId } = useAuth();
  const ent = useEntitlements();
  const f = ent.data?.features ?? [];
  const legacy = !!ent.data && !f.includes("ALERTS");
  const has = (x: string) => legacy || f.includes(x);
  const onb = useQuery({ queryKey: ["onboarding-state", orgId], enabled: !!orgId, queryFn: () => api<Onb>("/onboarding") });
  const s = useQuery({ queryKey: ["crm-summary"], queryFn: () => api<Summary>("/crm/summary") });
  const usage = useQuery({ queryKey: ["usage"], queryFn: () => api<UsageView>("/billing/usage") });
  const customers = useQuery({ queryKey: ["crm", "count"], queryFn: () => api<{ total: number }>("/crm/customers?size=1") });
  const autos = useQuery({ queryKey: ["automations"], queryFn: () => api<Automation[]>("/automations") });
  const currency = s.data?.currency ?? (usage.data ? MARKET_CURRENCY[usage.data.market] : "BRL");
  const firstName = me?.fullName?.split(" ")[0] ?? "";
  const store = onb.data?.storeName ?? "";

  const actions = [
    { href: "/app/contacts", icon: UserPlus, title: t("a.customers"), text: t("a.customersText") },
    { href: "/app/messages", icon: MessageSquareHeart, title: t("a.messages"), text: t("a.messagesText") },
    { href: "/app/sales", icon: Receipt, title: t("a.pdv"), text: t("a.pdvText") },
    { href: "/app/insights", icon: LineChart, title: t("a.insights"), text: t("a.insightsText") },
    { href: "/app/kanban", icon: KanbanSquare, title: t("a.kanban"), text: t("a.kanbanText"), feature: "PIPELINE" },
    { href: "/app/personas", icon: UsersRound, title: t("a.personas"), text: t("a.personasText"), feature: "PERSONAS" },
  ];
  const steps = [
    { done: onb.data?.whatsappChannelStatus === "ACTIVE" || onb.data?.emailChannelStatus === "ACTIVE", label: t("s.channels"), href: "/app/channels" },
    { done: (customers.data?.total ?? 0) >= 10, label: t("s.customers"), href: "/app/contacts" },
    { done: !!autos.data?.some((a) => a.description === PRESET_INACTIVE && a.status === "ACTIVE"), label: t("s.inactive"), href: "/app/messages" },
    { done: (s.data ? s.data.activeCustomers + (s.data.stages.CHURN_RISK ?? 0) + (s.data.stages.CHURNED ?? 0) : 0) > 0, label: t("s.sale"), href: "/app/sales" },
  ];
  const doneCount = steps.filter((x) => x.done).length;

  return (
    <div className="mx-auto max-w-6xl space-y-8">
      <section className="brand-glow rounded-2xl p-6 sm:p-8">
        <h1 className="text-3xl font-bold tracking-tight sm:text-4xl">{t("welcome", { name: firstName })}</h1>
        <p className="mt-2 max-w-2xl text-lg text-[#C9D2EA]">{t("love", { store })}</p>
        <p className="mt-6 text-xl font-semibold">{t("today")}</p>
      </section>

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {actions.map(({ href, icon: Icon, title, text, feature }) => {
          const locked = feature ? !has(feature) : false;
          return (
            <Link key={href} href={href} className={cn("group flex min-h-40 flex-col gap-3 rounded-2xl border bg-card p-5 transition-colors",
              locked ? "border-dashed border-border" : "border-border hover:border-primary")}>
              <span className="flex items-center justify-between">
                <span className="flex h-11 w-11 items-center justify-center rounded-xl bg-accent text-accent-foreground"><Icon className="h-5 w-5" aria-hidden /></span>
                {locked && feature && <PlanBadge feature={feature} />}
              </span>
              <span className="text-lg font-semibold group-hover:text-primary">{title}</span>
              <span className="text-sm text-muted-foreground">{text}</span>
            </Link>
          );
        })}
      </div>

      <div className="grid gap-4 lg:grid-cols-3">
        <Card className="lg:col-span-1">
          <div className="flex items-center justify-between"><h2 className="font-semibold">{t("firstSteps")}</h2><span className="text-sm tabular-nums text-muted-foreground">{doneCount}/{steps.length}</span></div>
          <ul className="mt-3 space-y-1">
            {steps.map((x) => (
              <li key={x.label}><Link href={x.href} className="flex min-h-10 items-center gap-3 rounded-lg px-2 text-sm hover:bg-muted">
                {x.done ? <CheckCircle2 className="h-5 w-5 text-success" aria-label={t("done")} /> : <Circle className="h-5 w-5 text-muted-foreground" aria-label={t("todo")} />}
                <span className={x.done ? "text-muted-foreground line-through" : ""}>{x.label}</span>
              </Link></li>
            ))}
          </ul>
        </Card>
        <Card>
          <h2 className="font-semibold">{t("numbers")}</h2>
          <dl className="mt-3 space-y-3 text-sm">
            <div className="flex justify-between gap-2"><dt className="text-muted-foreground">{t("recovered")}</dt><dd className="font-semibold tabular-nums">{money(s.data?.recoveredMinor ?? 0, currency, locale)}</dd></div>
            <div className="flex justify-between gap-2"><dt className="text-muted-foreground">{t("active")}</dt><dd className="font-semibold tabular-nums">{s.data?.activeCustomers ?? "—"}</dd></div>
            <div className="flex justify-between gap-2"><dt className="text-muted-foreground">{t("churnRisk")}</dt><dd className="font-semibold tabular-nums">{s.data?.stages.CHURN_RISK ?? "—"}</dd></div>
          </dl>
          <Link href="/app/insights" className="mt-4 inline-block text-sm text-primary">{t("understand")}</Link>
        </Card>
        <Card>
          <h2 className="font-semibold">{t("quota")}</h2>
          <QuotaBars className="mt-3" />
        </Card>
      </div>
    </div>
  );
}

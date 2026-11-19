"use client";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useLocale, useTranslations } from "next-intl";
import { CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { api } from "@/lib/api";
import { money } from "@/lib/format";
import { Alert, Card, CardTitle, PageHeader, Select, Spinner, Stat } from "@/components/ui";
import { OnboardingChecklist } from "@/features/onboarding/checklist";

interface Dashboard {
  activeAutomations: number; messagesSubmitted: number; messagesDelivered: number; messagesFailed: number; messagesBlocked: number; replies: number;
  missedCalls: number; callbacksDone: number; appointments: number; confirmedSales: number; confirmedRevenueByCurrency: Record<string, number>;
  attributedRevenueByCurrency: Record<string, number>; communicationCostMinor: number; walletCurrency: string; walletAvailableMinor: number;
  openTasks: number; openConversations: number; series: { day: string; sent: number; delivered: number; failed: number; replies: number }[];
}

export default function DashboardPage() {
  const t = useTranslations("dashboard");
  const locale = useLocale();
  const [days, setDays] = useState(30);
  const q = useQuery({ queryKey: ["dashboard", days], queryFn: () => api<Dashboard>(`/reports/dashboard?days=${days}`) });
  const d = q.data;
  const sumMoney = (m?: Record<string, number>) => (m && Object.keys(m).length ? Object.entries(m).map(([c, v]) => money(v, c, locale)).join(" · ") : "—");
  return (
    <div>
      <PageHeader title={t("title")} description={t("subtitle", { days })}
        actions={<Select aria-label="Period" value={days} onChange={(e) => setDays(Number(e.target.value))} className="w-36">
          {[7, 30, 90].map((n) => <option key={n} value={n}>{n}d</option>)}</Select>} />
      <OnboardingChecklist compact />
      {q.isLoading && <Spinner />}
      {q.error && <Alert tone="danger" title={String(q.error)} />}
      {d && (
        <>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <Stat label={t("activeAutomations")} value={d.activeAutomations} />
            <Stat label={t("sent")} value={d.messagesSubmitted} />
            <Stat label={t("delivered")} value={d.messagesDelivered} />
            <Stat label={t("failed")} value={d.messagesFailed} hint={`${t("blocked")}: ${d.messagesBlocked}`} />
            <Stat label={t("replies")} value={d.replies} />
            <Stat label={t("missedCalls")} value={d.missedCalls} hint={`${t("callbacks")}: ${d.callbacksDone}`} />
            <Stat label={t("sales")} value={d.confirmedSales} hint={`${t("appointments")}: ${d.appointments}`} />
            <Stat label={t("revenue")} value={<span className="text-lg">{sumMoney(d.confirmedRevenueByCurrency)}</span>} />
            <Stat label={t("attributed")} value={<span className="text-lg">{sumMoney(d.attributedRevenueByCurrency)}</span>} hint={t("attributedHint")} />
            <Stat label={t("cost")} value={money(d.communicationCostMinor, d.walletCurrency, locale)} />
            <Stat label={t("balance")} value={money(d.walletAvailableMinor, d.walletCurrency, locale)} />
            <Stat label={t("pending")} value={d.openTasks + d.openConversations} hint={`${d.openTasks} ${t("openTasks")} · ${d.openConversations} ${t("openConversations")}`} />
          </div>
          <Card className="mt-6">
            <CardTitle>{t("chart")}</CardTitle>
            <div className="mt-4 h-72">
              <ResponsiveContainer width="100%" height="100%">
                <LineChart data={d.series}>
                  <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
                  <XAxis dataKey="day" fontSize={12} stroke="var(--muted-foreground)" />
                  <YAxis allowDecimals={false} fontSize={12} stroke="var(--muted-foreground)" />
                  <Tooltip contentStyle={{ background: "var(--card)", border: "1px solid var(--border)" }} />
                  <Legend />
                  <Line type="monotone" dataKey="sent" stroke="var(--primary)" strokeWidth={2} dot={false} />
                  <Line type="monotone" dataKey="delivered" stroke="var(--success)" strokeWidth={2} dot={false} />
                  <Line type="monotone" dataKey="failed" stroke="var(--danger)" strokeWidth={2} dot={false} />
                  <Line type="monotone" dataKey="replies" stroke="var(--warning)" strokeWidth={2} dot={false} />
                </LineChart>
              </ResponsiveContainer>
            </div>
          </Card>
        </>
      )}
    </div>
  );
}

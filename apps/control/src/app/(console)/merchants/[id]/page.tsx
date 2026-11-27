"use client";
import Link from "next/link";
import { use } from "react";
import { useSearchParams } from "next/navigation";
import { usePoll } from "@/lib/use-poll";
import { Alert, Badge, Card, Title, fmt } from "@/components/ui";
import {
  BLOCK_LABEL, CHANNEL_LABEL, MARKET_LABEL, PLAN_LABEL, STATUS_LABEL, healthLabel, money, usdFromMicros,
  type MerchantDetail, type Target,
} from "@/lib/commercial";

/** Ficha do lojista: subscription, channels (status only — never secrets), quota usage, blocked sends and results. */
export default function MerchantPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const target = (useSearchParams().get("target") === "LIVE" ? "LIVE" : "TEST") as Target;
  const r = usePoll<MerchantDetail>(`/api/merchants/${id}?target=${target}`, 60_000);
  if (r.error) return <Alert tone="bad">{r.error}</Alert>;
  if (!r.data) return <p className="text-sm text-muted-foreground">Carregando…</p>;
  const d = r.data;
  const m = d.merchant;
  const row = (k: string, v: React.ReactNode) => <div className="flex justify-between gap-4 border-t border-border py-2 text-sm first:border-0"><dt className="text-muted-foreground">{k}</dt><dd className="text-right">{v}</dd></div>;
  return (
    <div className="space-y-4">
      <Link href="/merchants" className="text-sm text-primary">← Lojistas</Link>
      <div>
        <h1 className="text-2xl font-semibold">{m.tradeName}</h1>
        <p className="text-sm text-muted-foreground">{d.legalName} · {MARKET_LABEL[m.market]} ({m.countryCode}) · cliente desde {new Date(m.createdAt).toLocaleDateString("pt-BR")}</p>
        <div className="mt-2 flex flex-wrap gap-1">{m.health.length === 0 ? <Badge tone="ok">Saúde boa</Badge> : m.health.map((h) => <Badge key={h} tone="warn">{healthLabel(h)}</Badge>)}</div>
      </div>
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Card><p className="text-sm text-muted-foreground">Receita mensal</p><p className="mt-1 text-2xl font-semibold tabular-nums">{money(m.priceMinor, m.priceCurrency)}</p></Card>
        <Card><p className="text-sm text-muted-foreground">Custo de provedores no mês</p><p className="mt-1 text-2xl font-semibold tabular-nums">{usdFromMicros(m.providerCostMicrosThisMonth)}</p></Card>
        <Card><p className="text-sm text-muted-foreground">Vendas que recuperou no mês</p><p className="mt-1 text-2xl font-semibold tabular-nums">{d.recoveredSalesThisMonth}</p>
          <p className="text-xs text-muted-foreground">{money(d.recoveredMinorThisMonth, m.priceCurrency ?? undefined)} (moeda da loja)</p></Card>
        <Card><p className="text-sm text-muted-foreground">Clientes da loja</p><p className="mt-1 text-2xl font-semibold tabular-nums">{d.contacts.toLocaleString("pt-BR")}</p></Card>
      </div>
      <div className="grid gap-4 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <Title>Canais e credenciais</Title>
          <p className="mb-2 text-xs text-muted-foreground">Os segredos ficam criptografados na API; aqui aparecem só estado e erros.</p>
          {d.channels.length === 0 && <p className="text-sm text-muted-foreground">Nenhum canal conectado.</p>}
          <ul className="divide-y divide-border">
            {d.channels.map((c) => (
              <li key={c.channel} className="flex flex-wrap items-start justify-between gap-2 py-2.5 text-sm">
                <div><b>{CHANNEL_LABEL[c.channel] ?? c.channel}</b> <span className="text-muted-foreground">· {c.provider}{c.displayName ? ` · ${c.displayName}` : ""}</span>
                  {c.statusReason && <p className="text-xs text-muted-foreground">{c.statusReason}</p>}
                  {c.actionRequired && <p className="text-xs">Ação: {c.actionRequired}</p>}
                  <p className="text-xs text-muted-foreground">verificado {fmt(c.lastVerifiedAt)}{c.lastErrorAt ? ` · último erro ${fmt(c.lastErrorAt)}` : ""}</p></div>
                <Badge tone={c.status === "ACTIVE" ? "ok" : ["ERROR", "DISCONNECTED", "SUSPENDED"].includes(c.status) ? "bad" : "warn"}>{c.status}</Badge>
              </li>
            ))}
          </ul>
          <Title>Envios bloqueados (7 dias)</Title>
          {Object.keys(d.blockedLast7Days).length === 0 ? <p className="text-sm text-muted-foreground">Nenhum.</p> : (
            <dl>{Object.entries(d.blockedLast7Days).map(([k, n]) => row(BLOCK_LABEL[k] ?? k, n.toLocaleString("pt-BR")))}</dl>
          )}
        </Card>
        <div className="space-y-4">
          <Card>
            <Title>Assinatura</Title>
            <dl>
              {row("Plano", m.planCode ? PLAN_LABEL[m.planCode] ?? m.planCode : "—")}
              {row("Situação", STATUS_LABEL[m.subscriptionStatus ?? "NONE"] ?? m.subscriptionStatus)}
              {row("Preço", money(m.priceMinor, m.priceCurrency))}
              {row("Próxima cobrança", d.currentPeriodEnd ? new Date(d.currentPeriodEnd).toLocaleDateString("pt-BR") : "—")}
              {row("Documento fiscal", d.taxIdType ? `${d.taxIdType} · ${d.taxIdStatus}` : "—")}
              {row("E-mail", m.contactEmail)}
            </dl>
          </Card>
          <Card>
            <Title>Cota do período</Title>
            <ul className="space-y-3">
              {d.quotas.map((q) => {
                const pct = q.monthlyLimit ? Math.min(100, Math.round((q.used / q.monthlyLimit) * 100)) : 0;
                return (
                  <li key={q.channel} className="text-sm">
                    <div className="flex justify-between"><span>{CHANNEL_LABEL[q.channel]}</span>
                      <span className="tabular-nums text-muted-foreground">{q.payPerUse ? "pago por uso" : q.unlimited ? `${q.used} · ilimitado` : `${q.used} de ${q.monthlyLimit}`}</span></div>
                    {!q.payPerUse && !q.unlimited && <div className="mt-1 h-2 overflow-hidden rounded-full bg-muted" role="progressbar" aria-valuenow={pct} aria-valuemin={0} aria-valuemax={100} aria-label={CHANNEL_LABEL[q.channel]}>
                      <div className={pct >= 100 ? "h-full bg-danger" : pct >= 80 ? "h-full bg-warning" : "h-full bg-primary"} style={{ width: `${pct}%` }} /></div>}
                  </li>
                );
              })}
            </ul>
          </Card>
        </div>
      </div>
    </div>
  );
}

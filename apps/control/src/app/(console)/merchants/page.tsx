"use client";
import Link from "next/link";
import { useState } from "react";
import { usePoll } from "@/lib/use-poll";
import { Alert, Badge, Button, Card, Input, cn } from "@/components/ui";
import {
  CHANNEL_LABEL, MARKET_LABEL, PLAN_LABEL, STATUS_LABEL, healthLabel, money, usdFromMicros,
  type MerchantPage, type Target,
} from "@/lib/commercial";

const CHANNEL_TONE: Record<string, "ok" | "warn" | "bad" | "neutral"> = { ACTIVE: "ok", DEGRADED: "warn", PENDING_VERIFICATION: "warn", PENDING_APPROVAL: "warn",
  PENDING_AUTHORIZATION: "warn", ERROR: "bad", DISCONNECTED: "bad", SUSPENDED: "bad" };

/** Lojistas: the operator's spreadsheet of merchants — market, plan, payment, channels, usage, cost and health. */
export default function MerchantsPage() {
  const [target, setTarget] = useState<Target>("TEST");
  const [q, setQ] = useState("");
  const [market, setMarket] = useState("");
  const [plan, setPlan] = useState("");
  const [status, setStatus] = useState("");
  const [page, setPage] = useState(0);
  const params = new URLSearchParams({ target, page: String(page), size: "50" });
  if (q.trim()) params.set("q", q.trim());
  if (market) params.set("market", market);
  if (plan) params.set("plan", plan);
  if (status) params.set("status", status);
  const r = usePoll<MerchantPage>(`/api/merchants?${params}`, 60_000);
  const pages = r.data ? Math.max(1, Math.ceil(r.data.total / r.data.size)) : 1;
  const sel = "h-10 rounded-lg border border-input bg-card px-2 text-sm";
  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">Lojistas</h1>
        <p className="text-sm text-muted-foreground">Seus clientes: plano, pagamento, canais, envios e custo do mês. Custos de provedores em dólar (estimados até a conciliação).</p>
      </div>
      <div className="flex flex-wrap gap-2">
        <label className="sr-only" htmlFor="m-target">Ambiente</label>
        <select id="m-target" className={sel} value={target} onChange={(e) => { setTarget(e.target.value as Target); setPage(0); }}>
          <option value="TEST">Homologação</option><option value="LIVE">Produção</option>
        </select>
        <label className="sr-only" htmlFor="m-q">Buscar</label>
        <Input id="m-q" className="min-w-56 flex-1" placeholder="Buscar loja, razão social ou e-mail" value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} />
        <label className="sr-only" htmlFor="m-market">Mercado</label>
        <select id="m-market" className={sel} value={market} onChange={(e) => { setMarket(e.target.value); setPage(0); }}>
          <option value="">Todos os mercados</option>{Object.entries(MARKET_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
        </select>
        <label className="sr-only" htmlFor="m-plan">Plano</label>
        <select id="m-plan" className={sel} value={plan} onChange={(e) => { setPlan(e.target.value); setPage(0); }}>
          <option value="">Todos os planos</option>{Object.entries(PLAN_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
        </select>
        <label className="sr-only" htmlFor="m-status">Situação</label>
        <select id="m-status" className={sel} value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }}>
          <option value="">Qualquer situação</option>
          {["ACTIVE", "PAST_DUE", "GRACE_PERIOD", "SUSPENDED", "CHECKOUT_PENDING", "NONE"].map((s) => <option key={s} value={s}>{STATUS_LABEL[s]}</option>)}
        </select>
      </div>
      {r.error && <Alert tone="bad">{r.error}</Alert>}
      {!r.data && !r.error && <p className="text-sm text-muted-foreground">Carregando…</p>}
      {r.data && r.data.items.length === 0 && <Card><p className="text-sm text-muted-foreground">Nenhum lojista com esses filtros.</p></Card>}
      {r.data && r.data.items.length > 0 && (
        <>
          <div className="overflow-x-auto rounded-lg border border-border bg-card">
            <table className="w-full min-w-[1100px] text-sm">
              <thead className="bg-muted/60 text-left text-xs text-muted-foreground"><tr>
                {["Loja", "Mercado", "Plano", "Situação", "Canais", "Envios no mês", "Receita", "Custo", "Último acesso", "Saúde"].map((h) =>
                  <th key={h} scope="col" className="whitespace-nowrap px-3 py-2.5 font-medium">{h}</th>)}
              </tr></thead>
              <tbody className="divide-y divide-border">
                {r.data.items.map((m) => (
                  <tr key={m.id} className="align-top hover:bg-muted/40">
                    <td className="px-3 py-2.5"><Link href={`/merchants/${m.id}?target=${target}`} className="font-medium hover:text-primary">{m.tradeName}</Link>
                      <div className="text-xs text-muted-foreground">{m.contactEmail}</div></td>
                    <td className="whitespace-nowrap px-3 py-2.5">{MARKET_LABEL[m.market]} <span className="text-xs text-muted-foreground">{m.countryCode}</span></td>
                    <td className="whitespace-nowrap px-3 py-2.5">{m.planCode ? PLAN_LABEL[m.planCode] ?? m.planCode : "—"}</td>
                    <td className="px-3 py-2.5"><Badge tone={m.subscriptionStatus === "ACTIVE" ? "ok" : m.subscriptionStatus ? "warn" : "neutral"}>{STATUS_LABEL[m.subscriptionStatus ?? "NONE"] ?? m.subscriptionStatus}</Badge></td>
                    <td className="px-3 py-2.5"><div className="flex flex-wrap gap-1">
                      {Object.keys(m.channels).length === 0 ? <span className="text-muted-foreground">—</span>
                        : Object.entries(m.channels).map(([ch, st]) => <Badge key={ch} tone={CHANNEL_TONE[st] ?? "neutral"}>{CHANNEL_LABEL[ch] ?? ch}</Badge>)}
                    </div></td>
                    <td className="whitespace-nowrap px-3 py-2.5 text-xs tabular-nums">
                      {Object.keys(m.messagesThisMonth).length ? Object.entries(m.messagesThisMonth).map(([ch, n]) => `${CHANNEL_LABEL[ch] ?? ch} ${n}`).join(" · ") : "—"}</td>
                    <td className="whitespace-nowrap px-3 py-2.5 tabular-nums">{money(m.priceMinor, m.priceCurrency)}</td>
                    <td className="whitespace-nowrap px-3 py-2.5 tabular-nums">{usdFromMicros(m.providerCostMicrosThisMonth)}</td>
                    <td className="whitespace-nowrap px-3 py-2.5 text-xs">{m.lastLoginAt ? new Date(m.lastLoginAt).toLocaleDateString("pt-BR") : "nunca"}</td>
                    <td className="px-3 py-2.5"><div className="flex flex-wrap gap-1">
                      {m.health.length === 0 ? <Badge tone="ok">Boa</Badge>
                        : m.health.map((h) => <Badge key={h} tone={["COST_ABOVE_REVENUE", "PAYMENT_ISSUE"].includes(h) || h.startsWith("CHANNEL_") ? "bad" : "warn"}>{healthLabel(h)}</Badge>)}
                    </div></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="flex items-center justify-between text-sm text-muted-foreground">
            <span>{r.data.total} lojista(s)</span>
            <div className={cn("flex items-center gap-2")}>
              <span className="tabular-nums">{page + 1} / {pages}</span>
              <Button variant="outline" disabled={page === 0} onClick={() => setPage(page - 1)}>Anterior</Button>
              <Button variant="outline" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>Próxima</Button>
            </div>
          </div>
        </>
      )}
    </div>
  );
}

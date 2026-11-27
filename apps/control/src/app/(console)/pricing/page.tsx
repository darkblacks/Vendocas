"use client";
import { useEffect, useState } from "react";
import { call, message } from "@/lib/client";
import { usePoll } from "@/lib/use-poll";
import { useMe } from "@/lib/use-me";
import { Alert, Badge, Button, Card, Field, Input, Title, cn } from "@/components/ui";
import {
  CHANNEL_LABEL, MARKET_LABEL, PLAN_LABEL, fromMinor, money, toMinor,
  type Channel, type CommercialView, type MarketCode, type MarketWithCountries, type PlanPricing, type Target,
} from "@/lib/commercial";

const CHANNELS: Channel[] = ["WHATSAPP", "EMAIL", "SMS"];

/** Mercados, planos e preços: per market — open/closed, languages, channel order, send window; per plan — price, Stripe price, offer, trial, quotas. */
export default function PricingPage() {
  const me = useMe();
  const [target, setTarget] = useState<Target>("TEST");
  const [market, setMarket] = useState<MarketCode>("BR");
  const q = usePoll<CommercialView>(`/api/commercial?target=${target}`, 0);
  const canEdit = me?.role === "OWNER";
  const m = q.data?.markets.find((x) => x.market.code === market);
  const p = q.data?.pricing.find((x) => x.market === market);
  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">Mercados, planos e preços</h1>
        <p className="text-sm text-muted-foreground">Mudanças de preço valem para novas assinaturas; quem já assina mantém o preço até você migrar. Sem cota cadastrada, o canal é cobrado por uso dos créditos do lojista.</p>
      </div>
      <div className="flex flex-wrap items-center gap-3">
        <Segmented label="Ambiente" value={target} onChange={(v) => setTarget(v as Target)} options={[["TEST", "Homologação"], ["LIVE", "Produção"]]} />
        <Segmented label="Mercado" value={market} onChange={(v) => setMarket(v as MarketCode)} options={(["BR", "LATAM", "NA", "EU"] as MarketCode[]).map((c) => [c, MARKET_LABEL[c]])} />
      </div>
      {q.error && <Alert tone="bad">{q.error}</Alert>}
      {!q.data && !q.error && <p className="text-sm text-muted-foreground">Carregando…</p>}
      {!canEdit && q.data && <Alert tone="info">Somente o dono pode alterar preços, cotas e mercados.</Alert>}
      {m && <MarketCard key={`${target}-${m.market.code}-${m.market.version}`} target={target} m={m} canEdit={canEdit} onSaved={q.reload} />}
      {p && (
        <div className="grid gap-4 xl:grid-cols-3">
          {p.plans.map((plan) => <PlanCard key={`${target}-${market}-${plan.planCode}-${plan.price?.id ?? "none"}-${JSON.stringify(plan.quotas)}`}
            target={target} market={market} currency={p.currency} plan={plan} canEdit={canEdit} onSaved={q.reload} />)}
        </div>
      )}
    </div>
  );
}

function Segmented({ label, value, options, onChange }: { label: string; value: string; options: [string, string][]; onChange: (v: string) => void }) {
  return (
    <div role="radiogroup" aria-label={label} className="inline-flex flex-wrap gap-1 rounded-lg bg-muted p-1">
      {options.map(([v, l]) => (
        <button key={v} type="button" role="radio" aria-checked={value === v} onClick={() => onChange(v)}
          className={cn("min-h-9 rounded-md px-3 text-sm", value === v ? "bg-card font-medium shadow-sm" : "text-muted-foreground")}>{l}</button>
      ))}
    </div>
  );
}

function useSave(onSaved: () => void) {
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ tone: "ok" | "bad"; text: string } | null>(null);
  const run = async (fn: () => Promise<unknown>, ok: string) => {
    setBusy(true); setMsg(null);
    try { await fn(); setMsg({ tone: "ok", text: ok }); onSaved(); } catch (e) { setMsg({ tone: "bad", text: message(e) }); } finally { setBusy(false); }
  };
  return { busy, msg, run };
}

function MarketCard({ target, m, canEdit, onSaved }: { target: Target; m: MarketWithCountries; canEdit: boolean; onSaved: () => void }) {
  const mk = m.market;
  const [f, setF] = useState({ locales: mk.locales.join(", "), defaultLocale: mk.defaultLocale, order: mk.channelOrder.join(", "),
    start: mk.sendWindowStart.slice(0, 5), end: mk.sendWindowEnd.slice(0, 5) });
  const { busy, msg, run } = useSave(onSaved);
  const save = (extra: Record<string, unknown> = {}) => run(() => call("/api/commercial/market", { method: "PUT", body: {
    target, market: mk.code, expectedVersion: mk.version, defaultLocale: f.defaultLocale.trim(),
    locales: f.locales.split(",").map((s) => s.trim()).filter(Boolean), channelOrder: f.order.split(",").map((s) => s.trim().toUpperCase()).filter(Boolean),
    sendWindowStart: f.start, sendWindowEnd: f.end, ...extra,
  } }), "Mercado salvo");
  return (
    <Card>
      <Title actions={<Badge tone={mk.enabled ? "ok" : "warn"}>{mk.enabled ? "À venda" : "Fechado"}</Badge>}>{mk.name} · {mk.currency}</Title>
      <p className="mb-3 text-sm text-muted-foreground">{m.merchants} lojista(s) · países: {m.countries.map((c) => c.countryCode).join(", ")}</p>
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
        <Field id="loc" label="Idiomas"><Input id="loc" disabled={!canEdit} value={f.locales} onChange={(e) => setF({ ...f, locales: e.target.value })} /></Field>
        <Field id="dloc" label="Idioma padrão"><Input id="dloc" disabled={!canEdit} value={f.defaultLocale} onChange={(e) => setF({ ...f, defaultLocale: e.target.value })} /></Field>
        <Field id="ord" label="Ordem dos canais" hint="WHATSAPP, EMAIL, SMS"><Input id="ord" disabled={!canEdit} value={f.order} onChange={(e) => setF({ ...f, order: e.target.value })} /></Field>
        <Field id="ws" label="Envios a partir de"><Input id="ws" type="time" disabled={!canEdit} value={f.start} onChange={(e) => setF({ ...f, start: e.target.value })} /></Field>
        <Field id="we" label="Envios até"><Input id="we" type="time" disabled={!canEdit} value={f.end} onChange={(e) => setF({ ...f, end: e.target.value })} /></Field>
      </div>
      {canEdit && (
        <div className="mt-3 flex flex-wrap gap-2">
          <Button loading={busy} onClick={() => save()}>Salvar mercado</Button>
          <Button variant="outline" loading={busy} onClick={() => {
            if (mk.enabled && !confirm(`Fechar o mercado ${mk.name}? Novos cadastros desse mercado serão recusados e a vitrine sai do ar. Quem já é cliente continua.`)) return;
            void save({ enabled: !mk.enabled });
          }}>{mk.enabled ? "Fechar mercado" : "Abrir mercado"}</Button>
        </div>
      )}
      {msg && <div className="mt-3"><Alert tone={msg.tone}>{msg.text}</Alert></div>}
    </Card>
  );
}

function PlanCard({ target, market, currency, plan, canEdit, onSaved }: { target: Target; market: MarketCode; currency: string; plan: PlanPricing; canEdit: boolean; onSaved: () => void }) {
  const pr = plan.price;
  const [f, setF] = useState({ amount: fromMinor(pr?.unitAmountMinor), stripe: pr?.stripePriceId ?? "", offer: pr?.offerText ?? "", trial: String(pr?.trialDays ?? 0) });
  useEffect(() => { setF({ amount: fromMinor(pr?.unitAmountMinor), stripe: pr?.stripePriceId ?? "", offer: pr?.offerText ?? "", trial: String(pr?.trialDays ?? 0) }); }, [pr]);
  const { busy, msg, run } = useSave(onSaved);
  const minor = toMinor(f.amount);
  const savePrice = () => {
    if (minor == null) return;
    if (pr && !confirm(`Trocar o preço de ${PLAN_LABEL[plan.planCode]} (${MARKET_LABEL[market]}) de ${money(pr.unitAmountMinor, currency)} para ${money(minor, currency)}? Vale para novas assinaturas.`)) return;
    void run(() => call("/api/commercial/price", { method: "PUT", body: { target, market, plan: plan.planCode, unitAmountMinor: minor,
      stripePriceId: f.stripe.trim() || null, offerText: f.offer.trim() || null, trialDays: Number(f.trial) || 0 } }), "Preço salvo");
  };
  return (
    <Card className={cn(plan.planCode === "ILIMITADO" && "border-2 border-primary")}>
      <Title actions={<span className="text-xs text-muted-foreground">{plan.activeSubscriptions} assinatura(s)</span>}>{PLAN_LABEL[plan.planCode] ?? plan.planName}</Title>
      {!pr && <Alert tone="warn">Sem preço neste mercado: o plano não está à venda aqui.</Alert>}
      {pr && !pr.stripePriceId && <Alert tone="warn">Sem preço do Stripe vinculado: aparece na vitrine, mas ainda não dá para comprar.</Alert>}
      <div className="mt-3 grid gap-3 sm:grid-cols-2">
        <Field id={`a-${plan.planCode}`} label={`Preço mensal (${currency})`} error={f.amount && minor == null ? "Valor inválido" : undefined}>
          <Input id={`a-${plan.planCode}`} inputMode="decimal" disabled={!canEdit} value={f.amount} onChange={(e) => setF({ ...f, amount: e.target.value })} />
        </Field>
        <Field id={`t-${plan.planCode}`} label="Teste grátis (dias)">
          <Input id={`t-${plan.planCode}`} type="number" min={0} max={90} disabled={!canEdit} value={f.trial} onChange={(e) => setF({ ...f, trial: e.target.value })} />
        </Field>
        <div className="sm:col-span-2"><Field id={`s-${plan.planCode}`} label="Preço no Stripe" hint="price_… criado no painel do Stripe com o mesmo valor e moeda">
          <Input id={`s-${plan.planCode}`} disabled={!canEdit} value={f.stripe} onChange={(e) => setF({ ...f, stripe: e.target.value })} placeholder="price_…" />
        </Field></div>
        <div className="sm:col-span-2"><Field id={`o-${plan.planCode}`} label="Oferta na vitrine">
          <Input id={`o-${plan.planCode}`} maxLength={200} disabled={!canEdit} value={f.offer} onChange={(e) => setF({ ...f, offer: e.target.value })} />
        </Field></div>
      </div>
      {canEdit && <Button className="mt-3" loading={busy} disabled={minor == null} onClick={savePrice}>Salvar preço</Button>}
      <div className="mt-5 border-t border-border pt-4">
        <p className="mb-2 text-sm font-medium">Cota mensal inclusa</p>
        <div className="space-y-3">
          {CHANNELS.map((ch) => <QuotaRow key={ch} target={target} market={market} plan={plan.planCode} channel={ch}
            quota={plan.quotas.find((x) => x.channel === ch)} canEdit={canEdit} onSaved={onSaved} />)}
        </div>
      </div>
      {msg && <div className="mt-3"><Alert tone={msg.tone}>{msg.text}</Alert></div>}
    </Card>
  );
}

function QuotaRow({ target, market, plan, channel, quota, canEdit, onSaved }: { target: Target; market: MarketCode; plan: string; channel: Channel;
  quota?: { monthlyLimit: number | null }; canEdit: boolean; onSaved: () => void }) {
  const mode = !quota ? "PAY" : quota.monthlyLimit == null ? "UNLIMITED" : "LIMIT";
  const [m, setM] = useState<"PAY" | "UNLIMITED" | "LIMIT">(mode);
  const [limit, setLimit] = useState(quota?.monthlyLimit != null ? String(quota.monthlyLimit) : "");
  const { busy, msg, run } = useSave(onSaved);
  const dirty = m !== mode || (m === "LIMIT" && limit !== String(quota?.monthlyLimit ?? ""));
  const id = `q-${plan}-${channel}`;
  return (
    <div>
      <div className="flex flex-wrap items-end gap-2">
        <div className="w-24 pb-2 text-sm">{CHANNEL_LABEL[channel]}</div>
        <label htmlFor={`${id}-m`} className="sr-only">Modo da cota de {CHANNEL_LABEL[channel]}</label>
        <select id={`${id}-m`} disabled={!canEdit} className="h-10 rounded-lg border border-input bg-card px-2 text-sm" value={m} onChange={(e) => setM(e.target.value as typeof m)}>
          <option value="PAY">Pago por uso</option><option value="LIMIT">Limite mensal</option><option value="UNLIMITED">Ilimitado (uso justo)</option>
        </select>
        {m === "LIMIT" && <><label htmlFor={id} className="sr-only">Mensagens por mês</label>
          <Input id={id} type="number" min={0} className="w-28" disabled={!canEdit} value={limit} onChange={(e) => setLimit(e.target.value)} /></>}
        {canEdit && dirty && <Button variant="outline" loading={busy} disabled={m === "LIMIT" && !(Number(limit) >= 0 && limit !== "")} onClick={() => {
          if (m === "UNLIMITED" && channel !== "EMAIL" && !confirm(`${CHANNEL_LABEL[channel]} ilimitado pode custar mais do que o plano em lojas grandes. Confirmar?`)) return;
          void run(() => call("/api/commercial/quota", { method: "PUT", body: { target, market, plan, channel,
            monthlyLimit: m === "LIMIT" ? Number(limit) : null, remove: m === "PAY" } }), "Cota salva");
        }}>Salvar</Button>}
      </div>
      {msg && msg.tone === "bad" && <p className="mt-1 text-xs text-danger">{msg.text}</p>}
    </div>
  );
}

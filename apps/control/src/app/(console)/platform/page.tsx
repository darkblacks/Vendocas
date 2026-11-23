"use client";
import { useEffect, useState } from "react";
import { call, message } from "@/lib/client";
import { usePoll } from "@/lib/use-poll";
import { useMe } from "@/lib/use-me";
import { Alert, Badge, Button, Card, Title, fmt } from "@/components/ui";
import { FISCAL_DOC_LABEL, STATE_LABEL, TARGET_LABEL, serviceDetail, serviceTone, type PlatformStatus, type TargetView } from "@/lib/platform";

export default function PlatformPage() {
  const me = useMe();
  const q = usePoll<TargetView[]>("/api/platform", 30_000);
  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">Ambiente, pagamentos e emissão fiscal</h1>
        <p className="text-sm text-muted-foreground">Ambiente, modo do Stripe e emissão fiscal de cada API. As opções são independentes: a homologação pode usar o Stripe em produção, e a emissão fiscal pode ficar desligada sem afetar cobranças e assinaturas.</p>
      </div>
      {q.error && <Alert tone="bad">{q.error}</Alert>}
      {!q.data && !q.error && <p className="text-sm text-muted-foreground">Carregando…</p>}
      {q.data?.map((t) => <TargetCard key={t.target} view={t} canEdit={me?.role === "OWNER"} onSaved={q.reload} />)}
    </div>
  );
}

function TargetCard({ view, canEdit, onSaved }: { view: TargetView; canEdit: boolean; onSaved: () => void }) {
  const p = view.platform;
  return (
    <Card>
      <Title actions={<span className="text-xs text-muted-foreground">verificado {fmt(view.checkedAt)}</span>}>{TARGET_LABEL[view.target]}</Title>
      {!view.configured && <Alert tone="info">Nenhuma API cadastrada para este ambiente. Cadastre-a em Credenciais → API Vendocas ({view.target === "TEST" ? "Teste / homologação" : "Produção"}).</Alert>}
      {view.configured && !view.reachable && <Alert tone="warn">API inacessível ({view.reason}). {view.target === "TEST" ? "O Codespace pode estar desligado." : ""}</Alert>}
      {view.configured && view.reachable && !p && <Alert tone="warn">A API respondeu, mas não informou as configurações (versão antiga da API?).</Alert>}
      {p && (
        <div className="space-y-4">
          <ul className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
            {p.services.map((s) => (
              <li key={s.key} className="flex items-start justify-between gap-2 rounded-lg border border-border p-3">
                <div className="min-w-0"><div className="text-sm font-medium">{s.label}</div>{s.detail && <div className="text-xs text-muted-foreground">{serviceDetail(s.key, s.detail)}</div>}</div>
                <Badge tone={serviceTone(s.state)}>{STATE_LABEL[s.state] ?? s.state}</Badge>
              </li>
            ))}
          </ul>
          {p.warnings.map((w) => <Alert key={w} tone={/bloqueado|reais|validade/i.test(w) ? "warn" : "info"}>{w}</Alert>)}
          {Object.keys(p.fiscalDocuments).length > 0 && (
            <p className="text-sm">Documentos fiscais: {Object.entries(p.fiscalDocuments).map(([k, n]) => `${n} ${FISCAL_DOC_LABEL[k] ?? k}`).join(" · ")}</p>
          )}
          <p className="text-xs text-muted-foreground">Versão {p.settings.version}{p.settings.updatedBy ? ` · alterado por ${p.settings.updatedBy}` : ""} · {fmt(p.settings.updatedAt)}</p>
          {canEdit && view.writable && <SettingsForm target={view.target} p={p} onSaved={onSaved} />}
          {canEdit && !view.writable && <Alert tone="info">Somente leitura: {view.adminTokenStored ? "a API não aceitou alterações (CONTROL_ADMIN_TOKEN não configurado na API)." : "cadastre o CONTROL_ADMIN_TOKEN desta API em Credenciais para alterar daqui."}</Alert>}
        </div>
      )}
    </Card>
  );
}

function Select<T extends string>({ id, label, value, options, onChange, hint }: { id: string; label: string; value: T; options: [T, string][]; onChange: (v: T) => void; hint?: string }) {
  return (
    <div className="space-y-1">
      <label htmlFor={id} className="block text-sm font-medium">{label}</label>
      <select id={id} className="h-10 w-full rounded-lg border border-input bg-card px-2 text-sm" value={value} onChange={(e) => onChange(e.target.value as T)}>
        {options.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
      </select>
      {hint && <p className="text-xs text-muted-foreground">{hint}</p>}
    </div>
  );
}

function SettingsForm({ target, p, onSaved }: { target: "TEST" | "LIVE"; p: PlatformStatus; onSaved: () => void }) {
  const [f, setF] = useState({ ...p.settings, fiscalAutomation: p.settings.fiscalAutomation ? "ON" : "OFF" });
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ tone: "ok" | "bad"; text: string } | null>(null);
  useEffect(() => { setF({ ...p.settings, fiscalAutomation: p.settings.fiscalAutomation ? "ON" : "OFF" }); }, [p.settings]);
  const changed = f.environment !== p.settings.environment || f.stripeMode !== p.settings.stripeMode
    || (f.fiscalAutomation === "ON") !== p.settings.fiscalAutomation || f.fiscalProvider !== p.settings.fiscalProvider;
  const realMoney = f.stripeMode === "LIVE" && p.settings.stripeMode !== "LIVE";
  return (
    <form className="space-y-3 border-t border-border pt-4" onSubmit={async (e) => {
      e.preventDefault();
      if (realMoney && !confirm("Ativar o Stripe em produção? A partir disso, checkouts e renovações cobram dinheiro real.")) return;
      setBusy(true); setMsg(null);
      try {
        await call("/api/platform", { method: "PUT", body: { target, environment: f.environment, stripeMode: f.stripeMode, fiscalAutomation: f.fiscalAutomation === "ON",
          fiscalProvider: f.fiscalProvider, expectedVersion: p.settings.version } });
        setMsg({ tone: "ok", text: "Configurações salvas na API." }); onSaved();
      } catch (err) { setMsg({ tone: "bad", text: message(err) }); } finally { setBusy(false); }
    }}>
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Select id={`env-${target}`} label="Ambiente" value={f.environment} onChange={(v) => setF({ ...f, environment: v })}
          options={[["HOMOLOGATION", "Homologação"], ["PRODUCTION", "Produção"]]} />
        <Select id={`stripe-${target}`} label="Stripe" value={f.stripeMode} onChange={(v) => setF({ ...f, stripeMode: v })}
          options={[["TEST", "Teste"], ["LIVE", "Produção (cobranças reais)"]]} hint={`Chave configurada na API: ${({ LIVE: "produção", TEST: "teste", NONE: "nenhuma", UNKNOWN: "tipo desconhecido" } as Record<string, string>)[p.stripeKeyMode] ?? p.stripeKeyMode}`} />
        <Select id={`auto-${target}`} label="Emissão fiscal automática" value={f.fiscalAutomation as "ON" | "OFF"} onChange={(v) => setF({ ...f, fiscalAutomation: v })}
          options={[["OFF", "Desativada"], ["ON", "Ativada"]]} />
        <Select id={`prov-${target}`} label="Provedor fiscal" value={f.fiscalProvider} onChange={(v) => setF({ ...f, fiscalProvider: v })}
          options={[["NONE", "Nenhum"], ["FOCUS_NFE", "Focus NFe"]]} hint={f.fiscalProvider === "FOCUS_NFE" ? (p.focusConfigured ? `Credencial presente na API (${p.focusEnvironment ?? "?"})` : "FOCUS_NFE_TOKEN ausente na API") : undefined} />
      </div>
      {f.fiscalAutomation === "OFF" && <p className="text-xs text-muted-foreground">Com a emissão automática desativada, os pagamentos continuam sendo registrados e cada obrigação fiscal fica “aguardando emissão manual”. Desativar a automação não dispensa a emissão.</p>}
      {msg && <Alert tone={msg.tone}>{msg.text}</Alert>}
      <Button type="submit" loading={busy} disabled={!changed}>Salvar alterações</Button>
    </form>
  );
}

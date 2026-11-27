"use client";
import { useEffect, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { Building2, Check, Mail, MessageCircle, ShoppingBag } from "lucide-react";
import { BUSINESS_GROUPS, defaultModel, type SalesModel } from "@/features/business-types";
import { api, ApiError, errorMessage } from "@/lib/api";
import { useRouter } from "@/i18n/navigation";
import { useAuth } from "@/lib/auth";
import { Logo } from "@/components/brand";
import { Alert, Button, Field, Input, Select, Spinner, cn } from "@/components/ui";
import { AlertFooterPreview } from "@/features/alert-footer";

interface State {
  completed: boolean; storeName?: string | null; businessSegment?: string | null; alertContact?: string | null;
  whatsappChoice?: "CONNECT_OWN" | "PROVISION_NUMBER" | null; emailChoice?: "OWN_ADDRESS" | "VENDOCAS_ADDRESS" | null; emailAddress?: string | null;
  whatsappChannelStatus?: string | null; emailChannelStatus?: string | null; salesModel?: SalesModel | null; alertEmail?: string | null;
}


/** Mandatory onboarding: store → WhatsApp → e-mail. No skip: every step needs an answer before the app opens. */
export default function OnboardingPage() {
  const t = useTranslations("journey.onboarding");
  const router = useRouter();
  const qc = useQueryClient();
  const { orgId } = useAuth();
  const q = useQuery({ queryKey: ["onboarding-state", orgId], enabled: !!orgId, queryFn: () => api<State>("/onboarding") });
  const [step, setStep] = useState(0);
  const [f, setF] = useState({ storeName: "", businessSegment: "", salesModel: "" as SalesModel | "", alertContact: "", alertEmail: "", whatsappChoice: "", emailChoice: "", emailAddress: "" });
  const tt = useTranslations("journey");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!q.data) return;
    if (q.data.completed) { router.replace("/app"); return; }
    const known = BUSINESS_GROUPS.some((g) => g.types.some((x) => x.key === q.data!.businessSegment));
    setF({ storeName: q.data.storeName ?? "", businessSegment: known ? q.data.businessSegment ?? "" : "", salesModel: q.data.salesModel ?? "", alertContact: q.data.alertContact ?? "", alertEmail: q.data.alertEmail ?? "",
      whatsappChoice: q.data.whatsappChoice ?? "", emailChoice: q.data.emailChoice ?? "", emailAddress: q.data.emailAddress ?? "" });
  }, [q.data, router]);

  const emailOk = (v: string) => /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(v.trim());
  const reachOk = (f.alertEmail.trim() === "" || emailOk(f.alertEmail)) && (emailOk(f.alertEmail) || f.alertContact.trim() !== "");
  const steps = [t("steps.store"), t("steps.whatsapp"), t("steps.email")];
  const valid = [
    f.storeName.trim() && f.businessSegment && f.salesModel && reachOk,
    !!f.whatsappChoice,
    f.emailChoice === "VENDOCAS_ADDRESS" || (f.emailChoice === "OWN_ADDRESS" && emailOk(f.emailAddress)),
  ];

  async function next() {
    setBusy(true); setError(null);
    try {
      const body = step === 0 ? { storeName: f.storeName.trim(), businessSegment: f.businessSegment, salesModel: f.salesModel, alertEmail: f.alertEmail.trim(), alertContact: f.alertContact.trim() }
        : step === 1 ? { whatsappChoice: f.whatsappChoice }
        : { emailChoice: f.emailChoice, emailAddress: f.emailChoice === "OWN_ADDRESS" ? f.emailAddress.trim() : null };
      await api("/onboarding", { method: "PUT", body });
      if (step < 2) { setStep(step + 1); return; }
      await api("/onboarding/complete", { method: "POST" });
      await qc.invalidateQueries({ queryKey: ["onboarding-state"] });
      router.replace("/app?welcome=1");
    } catch (e) {
      setError(e instanceof ApiError && e.code === "VALIDATION_FAILED" ? t("incomplete") : errorMessage(e));
    } finally { setBusy(false); }
  }

  return (
    <div className="dark brand-glow min-h-screen px-4 py-8 text-foreground sm:py-12">
      <div className="mx-auto flex max-w-xl flex-col items-center gap-6">
        <Logo size={36} />
        <ol className="flex flex-wrap justify-center gap-x-6 gap-y-2 text-sm" aria-label={t("progress")}>
          {steps.map((s, i) => (
            <li key={s} className={cn("flex items-center gap-2", i === step ? "font-semibold text-white" : "text-[#A3AECB]")} aria-current={i === step ? "step" : undefined}>
              <span className={cn("flex h-6 w-6 items-center justify-center rounded-full border text-xs font-semibold",
                i < step ? "border-transparent bg-[var(--brand-blue-light)] text-white" : i === step ? "brand-gradient border-transparent" : "border-[#4A5470]")}>
                {i < step ? <Check className="h-3.5 w-3.5" aria-hidden /> : i + 1}
              </span>{s}
            </li>
          ))}
        </ol>
        <div className="w-full rounded-2xl border border-white/10 bg-[#0A1022]/90 p-6 text-[#F5F7FF] shadow-2xl backdrop-blur sm:p-8">
          {!q.data ? <Spinner /> : (
            <div className="space-y-5">
              {step === 0 && (<>
                <Head title={t("store.title")} text={t("store.text")} />
                <Field label={t("store.name")} htmlFor="ob-name"><Input id="ob-name" value={f.storeName} onChange={(e) => setF({ ...f, storeName: e.target.value })} /></Field>
                <Field label={t("store.type")} htmlFor="ob-type">
                  <Select id="ob-type" value={f.businessSegment}
                    onChange={(e) => setF({ ...f, businessSegment: e.target.value, salesModel: e.target.value ? defaultModel(e.target.value) : "" })}>
                    <option value="">{t("store.choose")}</option>
                    {BUSINESS_GROUPS.map((g) => (
                      <optgroup key={g.group} label={tt(`typeGroups.${g.group}`)}>
                        {g.types.map((x) => <option key={x.key} value={x.key}>{tt(`types.${x.key}`)}</option>)}
                      </optgroup>
                    ))}
                  </Select>
                </Field>
                {f.businessSegment && (
                  <fieldset>
                    <legend className="mb-1.5 text-sm font-medium">{t("store.model")}</legend>
                    <div className="grid gap-2 sm:grid-cols-2" role="radiogroup">
                      {(["B2C", "B2B"] as const).map((m) => {
                        const suggested = defaultModel(f.businessSegment) === m;
                        const on = f.salesModel === m;
                        const Icon = m === "B2C" ? ShoppingBag : Building2;
                        return (
                          <button key={m} type="button" role="radio" aria-checked={on} onClick={() => setF({ ...f, salesModel: m })}
                            className={cn("flex flex-col items-start gap-1 rounded-xl border p-3 text-left transition-colors",
                              on ? "border-[var(--brand-orange)] bg-[var(--brand-orange)]/10" : "border-white/15 opacity-60 hover:opacity-100")}>
                            <span className="flex items-center gap-2 font-semibold"><Icon className="h-4 w-4 text-[var(--brand-blue-light)]" aria-hidden />{t(`store.${m}`)}</span>
                            <span className="text-xs text-[#A3AECB]">{t(`store.${m}text`)}</span>
                            {suggested && <span className="text-xs text-[#A3AECB]">{t("store.suggested", { type: tt(`types.${f.businessSegment}`) })}</span>}
                          </button>
                        );
                      })}
                    </div>
                  </fieldset>
                )}
                <fieldset className="space-y-3 rounded-xl border border-white/10 p-4">
                  <legend className="px-1 text-sm font-semibold">{t("store.reachTitle")}</legend>
                  <p className="text-xs text-[#A3AECB]">{t("store.reachText")}</p>
                  <Field label={t("store.email")} htmlFor="ob-alert-email" hint={t("store.emailHint")}>
                    <Input id="ob-alert-email" type="email" value={f.alertEmail} onChange={(e) => setF({ ...f, alertEmail: e.target.value })} placeholder="contato@minhaloja.com.br" />
                  </Field>
                  <Field label={t("store.contact")} htmlFor="ob-contact" hint={t("store.contactHint")}>
                    <Input id="ob-contact" value={f.alertContact} onChange={(e) => setF({ ...f, alertContact: e.target.value })} placeholder="(11) 98888-0000" />
                  </Field>
                  <div className="rounded-lg border border-dashed border-white/15 bg-white/5 p-3">
                    <p className="mb-1 text-[11px] uppercase tracking-wide text-[#A3AECB]">{tt("alertFooter.title")}</p>
                    <AlertFooterPreview store={f.storeName} email={emailOk(f.alertEmail) ? f.alertEmail : ""} phone={f.alertContact} className="text-[#C9D2EA]" />
                  </div>
                </fieldset>
              </>)}
              {step === 1 && (<>
                <Head title={t("whatsapp.title")} text={t("whatsapp.text")} />
                <Choice icon={MessageCircle} checked={f.whatsappChoice === "CONNECT_OWN"} onSelect={() => setF({ ...f, whatsappChoice: "CONNECT_OWN" })}
                  title={t("whatsapp.own")} text={t("whatsapp.ownText")} />
                <Choice icon={MessageCircle} checked={f.whatsappChoice === "PROVISION_NUMBER"} onSelect={() => setF({ ...f, whatsappChoice: "PROVISION_NUMBER" })}
                  title={t("whatsapp.new")} text={t("whatsapp.newText")} />
                {f.whatsappChoice === "CONNECT_OWN" && <p className="rounded-lg bg-white/5 p-3 text-sm text-[#C9D2EA]">{t("whatsapp.ownHow")}</p>}
                {f.whatsappChoice === "PROVISION_NUMBER" && <p className="rounded-lg bg-white/5 p-3 text-sm text-[#C9D2EA]">{t("whatsapp.newHow")}</p>}
              </>)}
              {step === 2 && (<>
                <Head title={t("email.title")} text={t("email.text")} />
                <Choice icon={Mail} checked={f.emailChoice === "OWN_ADDRESS"} onSelect={() => setF({ ...f, emailChoice: "OWN_ADDRESS" })}
                  title={t("email.own")} text={t("email.ownText")} />
                {f.emailChoice === "OWN_ADDRESS" && (
                  <Field label={t("email.address")} htmlFor="ob-email" hint={t("email.addressHint")}>
                    <Input id="ob-email" type="email" value={f.emailAddress} onChange={(e) => setF({ ...f, emailAddress: e.target.value })} placeholder="contato@minhaloja.com.br" />
                  </Field>
                )}
                <Choice icon={Mail} checked={f.emailChoice === "VENDOCAS_ADDRESS"} onSelect={() => setF({ ...f, emailChoice: "VENDOCAS_ADDRESS" })}
                  title={t("email.vendocas")} text={t("email.vendocasText")} />
              </>)}
              {error && <Alert tone="danger" title={error} />}
              <div className="flex items-center justify-between gap-3 pt-1">
                {step > 0 ? <button type="button" className="min-h-11 text-sm text-[#C9D2EA] underline-offset-4 hover:underline" onClick={() => setStep(step - 1)}>{t("back")}</button> : <span />}
                <button type="button" disabled={!valid[step] || busy} onClick={next}
                  className="brand-gradient inline-flex min-h-12 items-center rounded-xl px-6 font-semibold disabled:cursor-not-allowed disabled:opacity-50">
                  {busy ? "…" : step < 2 ? t("continue") : t("finish")}
                </button>
              </div>
            </div>
          )}
        </div>
        <p className="text-center text-xs text-[#A3AECB]">{t("noSkip")}</p>
      </div>
    </div>
  );
}

function Head({ title, text }: { title: string; text: string }) {
  return <div><h1 className="text-2xl font-bold tracking-tight">{title}</h1><p className="mt-1 text-[#C9D2EA]">{text}</p></div>;
}

function Choice({ icon: Icon, checked, onSelect, title, text }: { icon: typeof Mail; checked: boolean; onSelect: () => void; title: string; text: string }) {
  return (
    <button type="button" role="radio" aria-checked={checked} onClick={onSelect}
      className={cn("flex w-full items-start gap-3 rounded-xl border p-4 text-left transition-colors",
        checked ? "border-[var(--brand-orange)] bg-[var(--brand-orange)]/10" : "border-white/15 hover:border-white/40")}>
      <span className={cn("mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full border-2", checked ? "border-[var(--brand-orange)]" : "border-[#4A5470]")}>
        {checked && <span className="h-2.5 w-2.5 rounded-full bg-[var(--brand-orange)]" />}
      </span>
      <span><span className="flex items-center gap-2 font-semibold"><Icon className="h-4 w-4 text-[var(--brand-blue-light)]" aria-hidden />{title}</span>
        <span className="mt-1 block text-sm text-[#A3AECB]">{text}</span></span>
    </button>
  );
}

"use client";
import { useEffect, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { Check, Mail, MessageCircle } from "lucide-react";
import { api, ApiError, errorMessage } from "@/lib/api";
import { useRouter } from "@/i18n/navigation";
import { useAuth } from "@/lib/auth";
import { Logo } from "@/components/brand";
import { Alert, Button, Field, Input, Select, Spinner, cn } from "@/components/ui";

interface State {
  completed: boolean; storeName?: string | null; businessSegment?: string | null; alertContact?: string | null;
  whatsappChoice?: "CONNECT_OWN" | "PROVISION_NUMBER" | null; emailChoice?: "OWN_ADDRESS" | "VENDOCAS_ADDRESS" | null; emailAddress?: string | null;
  whatsappChannelStatus?: string | null; emailChannelStatus?: string | null;
}

const SEGMENTS = ["fashion", "shoes", "beauty", "food", "pet", "home", "health", "services", "electronics", "other"] as const;

/** Mandatory onboarding: store → WhatsApp → e-mail. No skip: every step needs an answer before the app opens. */
export default function OnboardingPage() {
  const t = useTranslations("journey.onboarding");
  const router = useRouter();
  const qc = useQueryClient();
  const { orgId } = useAuth();
  const q = useQuery({ queryKey: ["onboarding-state", orgId], enabled: !!orgId, queryFn: () => api<State>("/onboarding") });
  const [step, setStep] = useState(0);
  const [f, setF] = useState({ storeName: "", businessSegment: "", alertContact: "", whatsappChoice: "", emailChoice: "", emailAddress: "" });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!q.data) return;
    if (q.data.completed) { router.replace("/app"); return; }
    setF({ storeName: q.data.storeName ?? "", businessSegment: q.data.businessSegment ?? "", alertContact: q.data.alertContact ?? "",
      whatsappChoice: q.data.whatsappChoice ?? "", emailChoice: q.data.emailChoice ?? "", emailAddress: q.data.emailAddress ?? "" });
  }, [q.data, router]);

  const steps = [t("steps.store"), t("steps.whatsapp"), t("steps.email")];
  const valid = [
    f.storeName.trim() && f.businessSegment && f.alertContact.trim(),
    !!f.whatsappChoice,
    f.emailChoice === "VENDOCAS_ADDRESS" || (f.emailChoice === "OWN_ADDRESS" && /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(f.emailAddress.trim())),
  ];

  async function next() {
    setBusy(true); setError(null);
    try {
      const body = step === 0 ? { storeName: f.storeName.trim(), businessSegment: f.businessSegment, alertContact: f.alertContact.trim() }
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
                <Field label={t("store.segment")} htmlFor="ob-seg">
                  <Select id="ob-seg" value={f.businessSegment} onChange={(e) => setF({ ...f, businessSegment: e.target.value })}>
                    <option value="">{t("store.choose")}</option>
                    {SEGMENTS.map((s) => <option key={s} value={s}>{t(`segments.${s}`)}</option>)}
                  </Select>
                </Field>
                <Field label={t("store.contact")} htmlFor="ob-contact" hint={t("store.contactHint")}>
                  <Input id="ob-contact" value={f.alertContact} onChange={(e) => setF({ ...f, alertContact: e.target.value })} placeholder="(11) 98888-0000" />
                </Field>
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

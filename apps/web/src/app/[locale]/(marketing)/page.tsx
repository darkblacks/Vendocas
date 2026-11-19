import { getTranslations, setRequestLocale } from "next-intl/server";
import { Bell, CalendarClock, Filter, Send, ShieldCheck, Image as ImageIcon, PhoneMissed, Mail, MessageCircle, Smartphone } from "lucide-react";
import { Link } from "@/i18n/navigation";
import { PricingTable } from "@/features/marketing/pricing-table";

export default async function Landing({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  setRequestLocale(locale);
  const t = await getTranslations("landing");
  const steps = [
    { icon: Bell, title: t("how1"), text: t("how1d") },
    { icon: CalendarClock, title: t("how2"), text: t("how2d") },
    { icon: Filter, title: t("how3"), text: t("how3d") },
    { icon: Send, title: t("how4"), text: t("how4d") },
  ];
  const faqs = [1, 2, 3, 4, 5] as const;
  return (
    <>
      <section className="mx-auto max-w-6xl px-4 pb-16 pt-16 sm:pt-24">
        <div className="max-w-3xl">
          <h1 className="text-4xl font-bold tracking-tight sm:text-5xl">{t("heroTitle")}</h1>
          <p className="mt-5 text-lg text-muted-foreground">{t("heroSubtitle")}</p>
          <div className="mt-8 flex flex-wrap gap-3">
            <Link href="/register" className="rounded-lg bg-primary px-6 py-3 font-medium text-primary-foreground">{t("heroCta")}</Link>
            <a href="#pricing" className="rounded-lg border border-border px-6 py-3 font-medium">{t("heroSecondary")}</a>
          </div>
        </div>
        <div aria-hidden className="mt-12 grid gap-3 rounded-lg border border-border bg-card p-4 text-sm sm:grid-cols-4">
          {[t("flow1"), t("flow2"), t("flow3"), t("flow4")].map((s) => (
            <div key={s} className="rounded-md bg-accent px-3 py-2 font-mono text-xs text-accent-foreground">{s}</div>
          ))}
        </div>
      </section>

      <section id="how" className="border-t border-border bg-muted/40 py-16">
        <div className="mx-auto max-w-6xl px-4">
          <h2 className="text-3xl font-semibold">{t("howTitle")}</h2>
          <div className="mt-8 grid gap-6 sm:grid-cols-2 lg:grid-cols-4">
            {steps.map(({ icon: Icon, title, text }) => (
              <div key={title} className="rounded-lg border border-border bg-card p-5">
                <Icon className="h-6 w-6 text-primary" aria-hidden />
                <h3 className="mt-3 font-semibold">{title}</h3>
                <p className="mt-2 text-sm text-muted-foreground">{text}</p>
              </div>
            ))}
          </div>
          <h3 className="mt-12 text-xl font-semibold">{t("examplesTitle")}</h3>
          <ul className="mt-4 grid gap-2 sm:grid-cols-2">
            {(["ex1", "ex2", "ex3", "ex4", "ex5"] as const).map((k) => <li key={k} className="rounded-md border border-border bg-card px-4 py-3 text-sm">{t(k)}</li>)}
          </ul>
        </div>
      </section>

      <section className="mx-auto grid max-w-6xl gap-10 px-4 py-16 md:grid-cols-2">
        <div>
          <h2 className="text-2xl font-semibold">{t("channelsTitle")}</h2>
          <p className="mt-3 text-muted-foreground">{t("channelsText")}</p>
          <div className="mt-6 flex flex-wrap gap-3 text-sm">
            {[{ i: Mail, l: "Email" }, { i: MessageCircle, l: "WhatsApp" }, { i: Smartphone, l: "SMS / MMS" }, { i: PhoneMissed, l: "Missed calls" }].map(({ i: I, l }) => (
              <span key={l} className="inline-flex items-center gap-2 rounded-full border border-border px-3 py-1"><I className="h-4 w-4" aria-hidden />{l}</span>
            ))}
          </div>
        </div>
        <div>
          <h2 className="text-2xl font-semibold">{t("numberTitle")}</h2>
          <p className="mt-3 text-muted-foreground">{t("numberText")}</p>
        </div>
        <div>
          <ImageIcon className="h-6 w-6 text-primary" aria-hidden />
          <h2 className="mt-2 text-2xl font-semibold">{t("campaignsTitle")}</h2>
          <p className="mt-3 text-muted-foreground">{t("campaignsText")}</p>
        </div>
        <div>
          <ShieldCheck className="h-6 w-6 text-primary" aria-hidden />
          <h2 className="mt-2 text-2xl font-semibold">{t("securityTitle")}</h2>
          <p className="mt-3 text-muted-foreground">{t("securityText")}</p>
        </div>
      </section>

      <section id="pricing" className="border-t border-border bg-muted/40 py-16">
        <div className="mx-auto max-w-6xl px-4">
          <h2 className="text-3xl font-semibold">{t("pricingTitle")}</h2>
          <PricingTable />
          <p className="mt-6 max-w-3xl text-sm text-muted-foreground">{t("pricingNote")}</p>
        </div>
      </section>

      <section id="faq" className="mx-auto max-w-3xl px-4 py-16">
        <h2 className="text-3xl font-semibold">{t("faqTitle")}</h2>
        <div className="mt-6 divide-y divide-border rounded-lg border border-border bg-card">
          {faqs.map((n) => (
            <details key={n} className="group p-4">
              <summary className="cursor-pointer font-medium">{t(`faq${n}q`)}</summary>
              <p className="mt-2 text-sm text-muted-foreground">{t(`faq${n}a`)}</p>
            </details>
          ))}
        </div>
      </section>

      <section className="border-t border-border py-16 text-center">
        <h2 className="text-3xl font-semibold">{t("ctaTitle")}</h2>
        <p className="mx-auto mt-3 max-w-xl text-muted-foreground">{t("ctaText")}</p>
        <Link href="/register" className="mt-6 inline-block rounded-lg bg-primary px-6 py-3 font-medium text-primary-foreground">{t("heroCta")}</Link>
      </section>
    </>
  );
}

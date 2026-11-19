import { notFound } from "next/navigation";
import { getTranslations, setRequestLocale } from "next-intl/server";
import { Alert } from "@/components/ui";

const DOCS: Record<string, { title: string; sections: [string, string][] }> = {
  terms: {
    title: "Terms of Service",
    sections: [
      ["Service", "Vendocas provides software that lets businesses configure and run their own communication automations using channels they connect. The customer (the business) decides recipients, content, timing and purpose."],
      ["Customer responsibilities", "You must only contact people for whom you have a valid legal basis and, for promotional messages, the authorization required by applicable law and channel policies (e.g. LGPD, GDPR/ePrivacy, TCPA, CAN-SPAM, carrier and Meta policies). You must keep evidence of consent and honour opt-outs. Prohibited uses include spam, sender spoofing and circumventing consent checks."],
      ["Subscription and usage", "Plans are billed monthly per establishment. Third-party communication costs (WhatsApp, SMS, MMS, calls) are paid from prepaid credits and are not unlimited. Spend limits configured by you are enforced."],
      ["Suspension", "We may suspend sending for non-payment, security risk, abuse indicators or provider/carrier requirements, with notice where possible."],
      ["Data processing", "For customer contact data we act as processor on your behalf under the Data Processing Agreement."],
      ["Liability", "To be completed by counsel for each contracting entity and jurisdiction."],
    ],
  },
  privacy: {
    title: "Privacy Policy",
    sections: [
      ["Who we are", "Controller details of the Vendocas contracting entity: to be completed."],
      ["Data about our customers (account holders)", "Identification, billing and tax data, usage logs and security records, used to provide, bill and secure the service."],
      ["Data about your customers (end consumers)", "Processed only on your instructions as processor: contact data, consent records, message and event history."],
      ["International transfers", "Hosting region is chosen per organization (US, EU or BR). Transfers rely on adequacy decisions or standard contractual clauses where required."],
      ["Retention", "Configurable per organization; import files are deleted after the configured period; erased contacts keep only suppression entries."],
      ["Rights", "Access, rectification, deletion, portability and objection — via your account or support. End consumers should contact the business that messaged them; we assist the business."],
      ["Cookies", "The dashboard uses only strictly necessary cookies (session). The optional website SDK stores an anonymous identifier only after the visitor's consent is signalled by the site."],
    ],
  },
  cancellation: {
    title: "Cancellation Policy",
    sections: [
      ["How to cancel", "Owners or finance users can cancel at any time in Billing. Cancellation stops future renewals."],
      ["Effect", "Access continues until the end of the paid period; no partial refunds for the remaining days unless required by law. Downgrades take effect at the end of the period."],
      ["Credits", "Unused communication credits remain available while the account exists; refund conditions to be defined per jurisdiction."],
      ["Data", "Data is kept for the configured retention period so you can reactivate; you may request export or deletion at any time."],
      ["Reactivation", "Reactivating creates a new subscription; previous configuration is preserved where still within retention."],
    ],
  },
};

export default async function LegalDoc({ params }: { params: Promise<{ locale: string; doc: string }> }) {
  const { locale, doc } = await params;
  setRequestLocale(locale);
  const d = DOCS[doc];
  if (!d) notFound();
  const t = await getTranslations("legal");
  return (
    <article className="mx-auto max-w-3xl px-4 py-12">
      <h1 className="text-3xl font-bold">{d.title}</h1>
      <div className="mt-4"><Alert tone="warning" title={t("draftNotice")} /></div>
      {d.sections.map(([h, p]) => (
        <section key={h} className="mt-8">
          <h2 className="text-xl font-semibold">{h}</h2>
          <p className="mt-2 leading-relaxed text-muted-foreground">{p}</p>
        </section>
      ))}
    </article>
  );
}

export function generateStaticParams() {
  return Object.keys(DOCS).map((doc) => ({ doc }));
}

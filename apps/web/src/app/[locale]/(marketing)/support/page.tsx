import { setRequestLocale } from "next-intl/server";

export default async function Support({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  setRequestLocale(locale);
  return (
    <div className="mx-auto max-w-3xl px-4 py-12">
      <h1 className="text-3xl font-bold">Support</h1>
      <p className="mt-4 text-muted-foreground">Customers can open tickets from the dashboard (Settings → Support) and see their status there.
        Billing disputes, privacy requests and abuse reports are handled by our team; for data-subject requests about messages you received,
        please contact the business that sent them — we will assist that business.</p>
      <ul className="mt-6 list-disc space-y-2 pl-6 text-sm">
        <li>Status of providers and channels: shown live in Channels with the reason and the action required.</li>
        <li>Integration documentation: API reference available at <code>/swagger-ui.html</code> on the API host.</li>
      </ul>
    </div>
  );
}

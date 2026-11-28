"use client";
import { useQuery } from "@tanstack/react-query";
import { useLocale, useTranslations } from "next-intl";
import { Boxes, History, Package, ShoppingCart } from "lucide-react";
import { api } from "@/lib/api";
import { money } from "@/lib/format";
import { Link } from "@/i18n/navigation";
import { Button, Card, EmptyState, PageHeader, Spinner, Stat, Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui";
import { MARKET_CURRENCY, type Entitlements, type UsageView } from "@/features/markets";
import { PlanBadge } from "@/features/plan";
import { SellTab } from "@/features/pos/sell";
import { HistoryTab } from "@/features/pos/history";
import { ProductsTab } from "@/features/pos/products";
import { StockTab } from "@/features/pos/stock";

interface Summary { totalMinor: number; sales: number; averageTicketMinor?: number | null; newCustomers: number; currency?: string | null }

/**
 * Vendocas PDV: the store registers what a customer bought elsewhere (counter, WhatsApp, Instagram) — the Vendocas does not
 * sell. Every plan: "venda seca" (customer + amount) and the sales history. Alerta + Vendas and up: catalogue with photos,
 * categories, cost and margin, stock control and sales per product.
 */
export default function SalesPage() {
  const t = useTranslations("sales");
  const locale = useLocale();
  const ent = useQuery({ queryKey: ["entitlements"], queryFn: () => api<Entitlements>("/billing/entitlements") });
  const usage = useQuery({ queryKey: ["usage"], queryFn: () => api<UsageView>("/billing/usage") });
  const features = ent.data?.features ?? [];
  const legacy = !!ent.data && !features.includes("ALERTS");
  const allowed = legacy || features.includes("SALES_REGISTRY");
  const catalog = legacy || features.includes("PRODUCT_CATALOG");
  const currency = usage.data ? MARKET_CURRENCY[usage.data.market] : "USD";
  const summary = useQuery({ queryKey: ["sales", "summary"], enabled: !!allowed, queryFn: () => api<Summary>("/sales/summary") });

  if (ent.isLoading) return <Spinner />;
  if (!allowed) {
    return (
      <div>
        <PageHeader title={t("title")} description={t("subtitle")} />
        <EmptyState title={t("upgrade")} action={<Button asChild><Link href="/app/billing">{t("seePlans")}</Link></Button>} />
      </div>
    );
  }
  const s = summary.data;
  const tabs = [
    { key: "sell", icon: ShoppingCart, label: t("tabs.sell") },
    { key: "history", icon: History, label: t("tabs.history") },
    { key: "products", icon: Package, label: t("tabs.products"), locked: !catalog },
    { key: "stock", icon: Boxes, label: t("tabs.stock"), locked: !catalog },
  ];
  return (
    <div className="space-y-6">
      <PageHeader title={t("title")} description={t("subtitle")} />
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Stat label={t("soldToday")} value={money(s?.totalMinor ?? 0, s?.currency ?? currency, locale)} />
        <Stat label={t("count")} value={s?.sales ?? 0} />
        <Stat label={t("avgTicket")} value={money(s?.averageTicketMinor, s?.currency ?? currency, locale)} />
        <Stat label={t("newCustomers")} value={s?.newCustomers ?? 0} />
      </div>
      <Tabs defaultValue="sell">
        <TabsList>
          {tabs.map(({ key, icon: Icon, label, locked }) => (
            <TabsTrigger key={key} value={key} className="gap-2">
              <Icon className="h-4 w-4" aria-hidden />{label}{locked && <PlanBadge feature="PRODUCT_CATALOG" />}
            </TabsTrigger>
          ))}
        </TabsList>
        <TabsContent value="sell" className="mt-4"><SellTab currency={currency} catalog={catalog} /></TabsContent>
        <TabsContent value="history" className="mt-4"><HistoryTab catalog={catalog} /></TabsContent>
        <TabsContent value="products" className="mt-4">{catalog ? <ProductsTab currency={currency} /> : <Locked text={t("pos.lockedProducts")} />}</TabsContent>
        <TabsContent value="stock" className="mt-4">{catalog ? <StockTab currency={currency} /> : <Locked text={t("pos.lockedStock")} />}</TabsContent>
      </Tabs>
    </div>
  );
}

function Locked({ text }: { text: string }) {
  const t = useTranslations("sales");
  return (
    <Card className="flex flex-col items-start gap-3">
      <PlanBadge feature="PRODUCT_CATALOG" />
      <p className="max-w-xl text-sm text-muted-foreground">{text}</p>
      <Button asChild><Link href="/app/billing">{t("seePlans")}</Link></Button>
    </Card>
  );
}

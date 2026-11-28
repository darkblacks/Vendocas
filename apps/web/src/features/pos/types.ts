/** Vendocas PDV types (source of truth: the API's ProductService / SalesService). */
export const UNITS = ["UN", "KG", "G", "L", "ML", "M", "CX", "PCT", "PAR", "SERV"] as const;
export type Unit = (typeof UNITS)[number];
export const PAYMENTS = ["PIX", "CARD", "CASH", "BOLETO", "TRANSFER", "OTHER"] as const;
export const SALE_CHANNELS = ["STORE", "WHATSAPP", "INSTAGRAM", "ONLINE", "PHONE", "OTHER"] as const;

export interface Product {
  id: string; name: string; sku?: string | null; priceMinor: number; currency: string; category?: string | null; categoryId?: string | null;
  description?: string | null; costMinor?: number | null; barcode?: string | null; unit: Unit; trackStock: boolean; stockQuantity: number;
  minStock?: number | null; lowStock: boolean; photoUrl?: string | null; active: boolean;
}
export interface Category { id: string; name: string; position: number; products: number }
export interface SaleRow {
  id: string; occurredAt: string; contactId?: string | null; contactName?: string | null; status: "COMPLETED" | "CANCELED" | string;
  totalMinor: number; discountMinor: number; currency: string; salesChannel: string; paymentMethod: string; items: number; firstItem?: string | null;
}
export interface SaleItem { productId?: string | null; description: string; quantity: number; unitAmountMinor: number; unitCostMinor?: number | null; unit?: string | null }
export interface SaleDetail extends Omit<SaleRow, "items" | "firstItem"> {
  notes?: string | null; registeredBy?: string | null; canceledAt?: string | null; canceledBy?: string | null; cancelReason?: string | null; items: SaleItem[];
}
export interface Movement {
  id: string; productId: string; productName: string; kind: "INITIAL" | "PURCHASE" | "SALE" | "SALE_CANCELED" | "COUNT" | "LOSS";
  quantity: number; balanceAfter: number; unitCostMinor?: number | null; orderId?: string | null; reason?: string | null; createdBy?: string | null; createdAt: string;
}
export interface StockSummary {
  currency?: string | null; trackedProducts: number; units: number; valueAtCostMinor: number; valueAtPriceMinor: number;
  lowStock: number; outOfStock: number; withoutCost: number;
}
export interface ProductSales { productId?: string | null; name: string; category?: string | null; quantity: number; revenueMinor: number; costMinor?: number | null; marginMinor?: number | null }

/** Margin over the price, in whole percent; null without a cost. */
export function marginPct(priceMinor: number, costMinor?: number | null): number | null {
  if (costMinor == null || priceMinor <= 0) return null;
  return Math.round(((priceMinor - costMinor) / priceMinor) * 100);
}

/** "12,50" / "1.234,50" / "12.50" → minor units (a comma means dots are thousands separators). */
export function parseMoney(v: string): number {
  const s = v.trim();
  if (!s) return 0;
  const n = Number(s.includes(",") ? s.replace(/\./g, "").replace(",", ".") : s);
  return Number.isFinite(n) && n >= 0 ? Math.round(n * 100) : NaN;
}

export const toInput = (minor?: number | null) => (minor == null ? "" : (minor / 100).toFixed(2));

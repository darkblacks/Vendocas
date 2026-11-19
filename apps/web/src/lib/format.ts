const ZERO_DECIMAL = new Set(["JPY", "KRW"]);

export function money(minor: number | null | undefined, currency: string | null | undefined, locale?: string): string {
  if (minor == null || !currency) return "—";
  const factor = ZERO_DECIMAL.has(currency) ? 1 : 100;
  return new Intl.NumberFormat(locale, { style: "currency", currency }).format(minor / factor);
}

export function dateTime(iso: string | null | undefined, locale?: string, timeZone?: string): string {
  if (!iso) return "—";
  return new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeStyle: "short", timeZone }).format(new Date(iso));
}

export function date(iso: string | null | undefined, locale?: string): string {
  if (!iso) return "—";
  return new Intl.DateTimeFormat(locale, { dateStyle: "medium" }).format(new Date(iso));
}

export function toMinor(value: string): number {
  const n = Number(value.replace(",", "."));
  return Number.isFinite(n) ? Math.round(n * 100) : 0;
}

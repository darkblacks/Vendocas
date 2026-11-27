import { cn } from "@vendocas/design-system/cn";

/** Vendocas symbol + wordmark ("vendocas." with the orange dot). The wordmark is live text, so it stays sharp. */
export function Logo({ className, size = 32, showName = true, label = "Vendocas" }: { className?: string; size?: number; showName?: boolean; label?: string }) {
  return (
    <span className={cn("inline-flex items-center gap-2", className)}>
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img src="/brand/vendocas-simbolo-192.png" width={size} height={size} alt={showName ? "" : label} className="shrink-0" />
      {showName && <span className="text-xl font-bold lowercase tracking-tight" aria-label={label}>vendocas<span className="text-[var(--brand-orange)]">.</span></span>}
    </span>
  );
}

/** "Toda venda é o começo da próxima." — the last word in the brand orange. `text` must contain the highlighted word once. */
export function Slogan({ text, highlight, className, onDark = false }: { text: string; highlight: string; className?: string; onDark?: boolean }) {
  const i = text.lastIndexOf(highlight);
  return (
    <span className={cn("italic", className)}>
      {i < 0 ? text : (<>{text.slice(0, i)}<span className={cn("font-bold not-italic", onDark ? "text-[var(--brand-orange-light)]" : "text-primary dark:text-[var(--brand-orange-light)]")}>{highlight}</span>{text.slice(i + highlight.length)}</>)}
    </span>
  );
}

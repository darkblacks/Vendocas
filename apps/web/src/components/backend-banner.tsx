"use client";
import * as React from "react";
import { useTranslations } from "next-intl";
import { backendStatus, type BackendState } from "@/lib/backend-status";

/** Shown when the API is unreachable (homologation server off). Informational only: it never starts the server. */
export function BackendBanner() {
  const t = useTranslations("backend");
  const s = React.useSyncExternalStore(backendStatus.subscribe, backendStatus.get, backendStatus.get) as BackendState;
  const [now, setNow] = React.useState(() => Date.now());
  const [busy, setBusy] = React.useState(false);
  React.useEffect(() => {
    if (s.up) return;
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [s.up]);
  if (s.up) return null;
  const secs = s.nextProbeAt ? Math.max(0, Math.ceil((s.nextProbeAt - now) / 1000)) : null;
  return (
    <div role="status" aria-live="polite" className="sticky top-0 z-50 border-b border-amber-300 bg-amber-50 px-4 py-2 text-sm text-amber-900 dark:border-amber-700 dark:bg-amber-950 dark:text-amber-100">
      <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-2">
        <span><strong>{t("title")}</strong> {t("body")}</span>
        <span className="flex items-center gap-3">
          {secs !== null && <span className="text-xs opacity-80">{t("retryIn", { seconds: secs })}</span>}
          <button type="button" disabled={busy} className="rounded border border-current px-2 py-0.5 text-xs disabled:opacity-50"
            onClick={async () => { setBusy(true); try { await backendStatus.probeNow(); } finally { setBusy(false); } }}>{t("retry")}</button>
        </span>
      </div>
    </div>
  );
}

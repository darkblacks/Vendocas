/**
 * Vendocas tracking SDK.
 *
 * Privacy model:
 *  - Nothing is sent and nothing is stored until the site calls `setConsent(true)` (wire it to your
 *    cookie banner / CMP when tracking consent is required, e.g. in the EU).
 *  - Visitors are anonymous. They become linked to a contact ONLY when your backend issues a signed
 *    identity token (HMAC-SHA256 of "kind:value|ts" with your tracking secret) and you call `identify`.
 *  - Tab close is not used as an abandonment signal. `checkout_abandoned` is only an auxiliary
 *    signal sent when a later page view finds a checkout that started more than 30 minutes ago.
 */
export interface IdentityToken { kind: "email" | "phone" | "externalId"; value: string; ts: number; sig: string }
export interface SignedOrder { orderId: string; amountMinor: number; currency: string; sig: string }
export interface InitOptions { pk: string; endpoint?: string; consent?: boolean }
type EventName = "page_view" | "product_view" | "form_started" | "form_submitted" | "checkout_started" | "purchase_completed" | "checkout_abandoned";

const ANON_KEY = "vd_anon";
const CHECKOUT_KEY = "vd_checkout_at";
const ABANDON_AFTER_MS = 30 * 60 * 1000;

let cfg: Required<InitOptions> | null = null;

function storage(kind: "local" | "session"): Storage | null {
  try { return kind === "local" ? window.localStorage : window.sessionStorage; } catch { return null; }
}

function uid(): string {
  const a = new Uint8Array(16);
  crypto.getRandomValues(a);
  return Array.from(a, (b) => b.toString(16).padStart(2, "0")).join("");
}

function anonymousId(): string {
  const s = storage("local");
  let id = s?.getItem(ANON_KEY) ?? null;
  if (!id) { id = uid(); s?.setItem(ANON_KEY, id); }
  return id;
}

function sessionId(): string {
  const s = storage("session");
  let id = s?.getItem("vd_session") ?? null;
  if (!id) { id = uid(); s?.setItem("vd_session", id); }
  return id;
}

function send(event: EventName | "identify", extra: Record<string, unknown> = {}): Promise<void> {
  if (!cfg || !cfg.consent) return Promise.resolve();
  const body = JSON.stringify({ pk: cfg.pk, anonymousId: anonymousId(), sessionId: sessionId(), event, consent: true,
    url: location.href.slice(0, 1000), ...extra });
  return fetch(cfg.endpoint + "/api/v1/track", { method: "POST", headers: { "Content-Type": "application/json" }, body, keepalive: true, credentials: "omit" })
    .then(() => undefined).catch(() => undefined);
}

export function init(opts: InitOptions): void {
  cfg = { pk: opts.pk, endpoint: (opts.endpoint ?? "https://api.vendocas.com").replace(/\/$/, ""), consent: !!opts.consent };
  if (cfg.consent) afterConsent();
}

export function setConsent(granted: boolean): void {
  if (!cfg) return;
  cfg.consent = granted;
  if (granted) afterConsent();
  else { storage("local")?.removeItem(ANON_KEY); storage("session")?.removeItem(CHECKOUT_KEY); }
}

function afterConsent(): void {
  const started = Number(storage("session")?.getItem(CHECKOUT_KEY) ?? 0);
  if (started && Date.now() - started > ABANDON_AFTER_MS) {
    storage("session")?.removeItem(CHECKOUT_KEY);
    void send("checkout_abandoned");
  }
}

export function identify(identity: IdentityToken): Promise<void> { return send("identify", { identity }); }

export function track(event: Exclude<EventName, "purchase_completed" | "checkout_abandoned">): Promise<void> {
  if (event === "checkout_started") storage("session")?.setItem(CHECKOUT_KEY, String(Date.now()));
  return send(event);
}

/** Pass a server-signed order to have it counted as confirmed revenue; unsigned purchases only stop abandonment flows. */
export function purchase(order?: SignedOrder): Promise<void> {
  storage("session")?.removeItem(CHECKOUT_KEY);
  return send("purchase_completed", order ? { order } : {});
}

export function pageView(): Promise<void> { return send("page_view"); }

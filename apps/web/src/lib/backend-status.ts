/**
 * Tracks whether the Vendocas API (homologation runs in a GitHub Codespace that may be switched off) is reachable.
 * While it is down, API calls fail fast locally instead of hammering the proxy, and a single health probe retries
 * with exponential backoff. The web app NEVER starts the Codespace — that is only done from the Control Center.
 */
export type BackendState = { up: boolean; since: number; nextProbeAt: number | null };

const MIN_DELAY = 10_000;
const MAX_DELAY = 120_000;
let state: BackendState = { up: true, since: Date.now(), nextProbeAt: null };
let delay = MIN_DELAY;
let timer: ReturnType<typeof setTimeout> | null = null;
let probing: Promise<boolean> | null = null;
const listeners = new Set<(s: BackendState) => void>();

function set(next: BackendState) {
  state = next;
  listeners.forEach((l) => l(state));
}

export const backendStatus = {
  get: () => state,
  subscribe(l: (s: BackendState) => void) { listeners.add(l); return () => { listeners.delete(l); }; },
  markDown() {
    if (!state.up) return;
    delay = MIN_DELAY;
    schedule(Date.now());
  },
  markUp() {
    if (timer) clearTimeout(timer);
    timer = null;
    delay = MIN_DELAY;
    if (!state.up) set({ up: true, since: Date.now(), nextProbeAt: null });
  },
  /** Manual "try again" — still a single health request, never a burst. */
  probeNow: () => probe(),
  /** test hook */
  __reset() { if (timer) clearTimeout(timer); timer = null; probing = null; delay = MIN_DELAY; state = { up: true, since: Date.now(), nextProbeAt: null }; },
};

function schedule(downSince: number) {
  if (timer) clearTimeout(timer);
  const at = Date.now() + delay;
  set({ up: false, since: state.up ? downSince : state.since, nextProbeAt: at });
  timer = setTimeout(() => { void probe(); }, delay);
  delay = Math.min(delay * 2, MAX_DELAY);
}

async function probe(): Promise<boolean> {
  if (probing) return probing;
  probing = (async () => {
    let up = false;
    try {
      const r = await fetch("/api/health", { cache: "no-store" });
      up = r.ok && ((await r.json()) as { up?: boolean }).up === true;
    } catch { up = false; }
    if (up) backendStatus.markUp(); else schedule(state.since);
    return up;
  })().finally(() => { probing = null; });
  return probing;
}

/** True for responses that mean "the API process is not there" (proxy errors), as opposed to API-level errors. */
export function isUnavailableResponse(res: Response): boolean {
  if (res.status === 502 || res.status === 503 || res.status === 504) return !isApiJson(res) || res.status !== 503;
  return res.status >= 500 && !isApiJson(res);
}

function isApiJson(res: Response) {
  const ct = res.headers.get("content-type") ?? "";
  return ct.includes("application/json") || ct.includes("application/problem+json");
}

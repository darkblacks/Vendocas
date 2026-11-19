// Lightweight reachability probe for the Vendocas API (used by the "service unavailable" banner).
// Returns only up/down — never internal details. Does not start anything.
export const dynamic = "force-dynamic";

const API = process.env.API_INTERNAL_URL ?? "http://localhost:8080";

export async function GET() {
  let up = false;
  try {
    const r = await fetch(`${API}/actuator/health`, { signal: AbortSignal.timeout(3000), cache: "no-store" });
    up = r.ok && ((await r.json()) as { status?: string }).status === "UP";
  } catch { up = false; }
  return Response.json({ up }, { status: up ? 200 : 503, headers: { "Cache-Control": "no-store" } });
}

import { z } from "zod";
import { requireAuth } from "@/server/auth";
import { handle, json } from "@/server/http";
import { merchants } from "@/server/commercial";

export const dynamic = "force-dynamic";

/** The operator's merchant spreadsheet (tenant data: ADMIN and above). */
export const GET = handle(async (req: Request) => {
  const ctx = await requireAuth(req, { role: "ADMIN" });
  const s = new URL(req.url).searchParams;
  const target = z.enum(["TEST", "LIVE"]).parse(s.get("target") ?? "TEST");
  return json(await merchants(ctx, target, {
    q: s.get("q") ?? undefined, market: s.get("market") || undefined, plan: s.get("plan") || undefined, status: s.get("status") || undefined,
    page: Number(s.get("page") ?? 0) || 0, size: Number(s.get("size") ?? 50) || 50,
  }));
});

import { z } from "zod";
import { requireAuth } from "@/server/auth";
import { handle, json } from "@/server/http";
import { markets, pricing } from "@/server/commercial";

export const dynamic = "force-dynamic";
const Target = z.enum(["TEST", "LIVE"]);

/** Markets + prices/quotas of every plan per market, for the "Mercados e preços" screen. */
export const GET = handle(async (req: Request) => {
  const ctx = await requireAuth(req, { role: "VIEWER" });
  const target = Target.parse(new URL(req.url).searchParams.get("target") ?? "TEST");
  const [m, p] = await Promise.all([markets(ctx, target), pricing(ctx, target)]);
  return json({ target, markets: m, pricing: p });
});

import { z } from "zod";
import { requireAuth } from "@/server/auth";
import { body, handle, json } from "@/server/http";
import { setPrice } from "@/server/commercial";

export const dynamic = "force-dynamic";
const Input = z.object({
  target: z.enum(["TEST", "LIVE"]), market: z.enum(["BR", "LATAM", "NA", "EU"]), plan: z.enum(["ALERTA", "ALERTA_VENDAS", "ILIMITADO"]),
  unitAmountMinor: z.number().int().positive().max(100_000_000),
  stripePriceId: z.string().regex(/^price_[A-Za-z0-9]{6,100}$/).nullable().optional(),
  offerText: z.string().max(200).nullable().optional(), trialDays: z.number().int().min(0).max(90).nullable().optional(),
});

/** OWNER with recent re-authentication: prices decide what customers pay. */
export const PUT = handle(async (req: Request) => {
  const ctx = await requireAuth(req, { role: "OWNER", reauth: true });
  const { target, market, plan, ...change } = await body(req, (v) => Input.parse(v));
  return json(await setPrice(ctx, target, market, plan, change));
});

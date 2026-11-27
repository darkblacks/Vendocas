import { z } from "zod";
import { requireAuth } from "@/server/auth";
import { body, handle, json } from "@/server/http";
import { setQuota } from "@/server/commercial";

export const dynamic = "force-dynamic";
const Input = z.object({
  target: z.enum(["TEST", "LIVE"]), market: z.enum(["BR", "LATAM", "NA", "EU"]), plan: z.enum(["ALERTA", "ALERTA_VENDAS", "ILIMITADO"]),
  channel: z.enum(["EMAIL", "WHATSAPP", "SMS"]), monthlyLimit: z.number().int().min(0).max(100_000_000).nullable(),
  fairUseNote: z.string().max(300).nullable().optional(), remove: z.boolean().optional(),
});

/** OWNER with recent re-authentication: quotas decide the margin of each plan. */
export const PUT = handle(async (req: Request) => {
  const ctx = await requireAuth(req, { role: "OWNER", reauth: true });
  const { target, market, plan, channel, ...change } = await body(req, (v) => Input.parse(v));
  return json(await setQuota(ctx, target, market, plan, channel, change));
});

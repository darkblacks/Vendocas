import { z } from "zod";
import { requireAuth } from "@/server/auth";
import { body, handle, json } from "@/server/http";
import { updateMarket } from "@/server/commercial";
/** The platform speaks six languages, no regional variants (same rule as the API). */
const LANGUAGES = ["pt", "en", "es", "fr", "de", "it"] as const;

export const dynamic = "force-dynamic";
const Input = z.object({
  target: z.enum(["TEST", "LIVE"]), market: z.enum(["BR", "LATAM", "US", "CA", "EU"]),
  name: z.string().min(1).max(60).optional(), defaultLocale: z.enum(LANGUAGES).optional(), locales: z.array(z.enum(LANGUAGES)).min(1).max(6).optional(),
  channelOrder: z.array(z.enum(["EMAIL", "WHATSAPP", "SMS"])).max(3).optional(),
  sendWindowStart: z.string().regex(/^\d{2}:\d{2}(:\d{2})?$/).optional(), sendWindowEnd: z.string().regex(/^\d{2}:\d{2}(:\d{2})?$/).optional(),
  enabled: z.boolean().optional(), expectedVersion: z.number().int().nonnegative().optional(),
});

/** OWNER with recent re-authentication: opening or closing a market changes who can sign up. */
export const PUT = handle(async (req: Request) => {
  const ctx = await requireAuth(req, { role: "OWNER", reauth: true });
  const { target, market, ...change } = await body(req, (v) => Input.parse(v));
  return json(await updateMarket(ctx, target, market, change));
});

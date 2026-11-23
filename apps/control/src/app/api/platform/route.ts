import { z } from "zod";
import { requireAuth } from "@/server/auth";
import { body, handle, json } from "@/server/http";
import { overview, update } from "@/server/platform";

export const dynamic = "force-dynamic";

export const GET = handle(async (req: Request) => {
  await requireAuth(req, { role: "VIEWER" });
  return json(await overview());
});

const Input = z.object({
  target: z.enum(["TEST", "LIVE"]),
  environment: z.enum(["HOMOLOGATION", "PRODUCTION"]).optional(),
  stripeMode: z.enum(["TEST", "LIVE"]).optional(),
  fiscalAutomation: z.boolean().optional(),
  fiscalProvider: z.enum(["NONE", "FOCUS_NFE"]).optional(),
  expectedVersion: z.number().int().positive().optional(),
});

/** OWNER only, with recent re-authentication: these switches decide whether real money is charged. */
export const PUT = handle(async (req: Request) => {
  const ctx = await requireAuth(req, { role: "OWNER", reauth: true });
  const { target, ...change } = await body(req, (v) => Input.parse(v));
  return json(await update(ctx, target, change));
});

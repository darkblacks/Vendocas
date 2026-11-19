import { z } from "zod";
import { requireAuth } from "@/server/auth";
import { setMaxHours, settings } from "@/server/codespaces";
import { body, handle, json } from "@/server/http";

export const dynamic = "force-dynamic";
export const GET = handle(async (req: Request) => {
  await requireAuth(req, { role: "VIEWER" });
  return json({ ...(await settings()), links: { web: process.env.CONTROL_WEB_APP_URL ?? null } });
});
const Input = z.object({ maxSessionHours: z.number().gt(0).lte(6) });
export const PUT = handle(async (req: Request) => {
  const ctx = await requireAuth(req, { role: "OWNER", reauth: true });
  await setMaxHours(ctx, (await body(req, (v) => Input.parse(v))).maxSessionHours);
  return json({ ok: true });
});

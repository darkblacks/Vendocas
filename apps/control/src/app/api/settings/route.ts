import { z } from "zod";
import { requireAuth } from "@/server/auth";
import { setMaxHours, setRepository, setWebAppUrl, settings } from "@/server/codespaces";
import { body, handle, json } from "@/server/http";

export const dynamic = "force-dynamic";
export const GET = handle(async (req: Request) => {
  await requireAuth(req, { role: "VIEWER" });
  const s = await settings();
  return json({ ...s, links: { web: s.webAppUrl } });
});

// Everything here used to be environment variables; now it is configured in the UI (OWNER + recent re-authentication).
const Input = z.object({
  maxSessionHours: z.number().gt(0).lte(6).optional(),
  githubRepository: z.string().min(3).max(140).optional(),
  webAppUrl: z.string().max(300).nullable().optional(),
});
export const PUT = handle(async (req: Request) => {
  const ctx = await requireAuth(req, { role: "OWNER", reauth: true });
  const i = await body(req, (v) => Input.parse(v));
  const out: Record<string, unknown> = { ok: true };
  if (i.maxSessionHours !== undefined) await setMaxHours(ctx, i.maxSessionHours);
  if (i.githubRepository !== undefined) out.repository = await setRepository(ctx, i.githubRepository);
  if (i.webAppUrl !== undefined) out.webAppUrl = await setWebAppUrl(ctx, i.webAppUrl);
  return json(out);
});

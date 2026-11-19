import { z } from "zod";
import { requireAuth } from "@/server/auth";
import { select } from "@/server/codespaces";
import { body, handle, json } from "@/server/http";

export const dynamic = "force-dynamic";
const Input = z.object({ name: z.string().regex(/^[A-Za-z0-9][A-Za-z0-9-]{1,98}$/) });
export const POST = handle(async (req: Request) => {
  const ctx = await requireAuth(req, { role: "OWNER", reauth: true });
  await select(ctx, (await body(req, (v) => Input.parse(v))).name);
  return json({ ok: true });
});

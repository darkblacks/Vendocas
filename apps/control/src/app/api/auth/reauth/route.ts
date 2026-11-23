import { z } from "zod";
import { reauthenticate, requireAuth } from "@/server/auth";
import { body, handle, json } from "@/server/http";

export const dynamic = "force-dynamic";
// `code` is accepted for compatibility with older clients and ignored: re-authentication is password-only.
const Input = z.object({ password: z.string().min(1).max(200), code: z.string().max(12).optional() });

export const POST = handle(async (req: Request) => {
  const ctx = await requireAuth(req);
  const i = await body(req, (v) => Input.parse(v));
  await reauthenticate(req, ctx, i.password);
  return json({ ok: true });
});

import { z } from "zod";
import { changePassword, requireAuth } from "@/server/auth";
import { body, handle, json } from "@/server/http";

export const dynamic = "force-dynamic";
const Input = z.object({ currentPassword: z.string().min(1).max(200), newPassword: z.string().min(14).max(200) });

export const POST = handle(async (req: Request) => {
  const ctx = await requireAuth(req, { reauth: true });
  const i = await body(req, (v) => Input.parse(v));
  await changePassword(ctx, i.currentPassword, i.newPassword);
  return json({ ok: true });
});

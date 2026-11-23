import { z } from "zod";
import { listDevices, requireAuth, revokeAllSessions, revokeDevice } from "@/server/auth";
import { body, handle, json } from "@/server/http";

export const dynamic = "force-dynamic";

/** The signed-in user's trusted devices (browsers where sign-in is e-mail + password). */
export const GET = handle(async (req: Request) => {
  const ctx = await requireAuth(req);
  return json(await listDevices(ctx.user.id, req));
});

const Input = z.discriminatedUnion("action", [
  z.object({ action: z.literal("revoke"), id: z.uuid() }),
  z.object({ action: z.literal("sign_out_everywhere") }),
]);

export const POST = handle(async (req: Request) => {
  const ctx = await requireAuth(req, { reauth: true });
  const i = await body(req, (v) => Input.parse(v));
  if (i.action === "revoke") { await revokeDevice(ctx, i.id); return json({ ok: true }); }
  return json({ revoked: await revokeAllSessions(ctx, ctx.user.id) });
});

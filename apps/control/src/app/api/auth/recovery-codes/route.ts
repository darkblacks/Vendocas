import { regenerateRecoveryCodes, requireAuth } from "@/server/auth";
import { handle, json } from "@/server/http";

export const dynamic = "force-dynamic";

/** New set of 10 single-use recovery codes (the previous set stops working). Shown once. */
export const POST = handle(async (req: Request) => {
  const ctx = await requireAuth(req, { reauth: true });
  return json({ recoveryCodes: await regenerateRecoveryCodes(ctx) });
});

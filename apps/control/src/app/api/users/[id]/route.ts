import { z } from "zod";
import { requireAuth, resetMfa, revokeAllSessions, setUserStatus } from "@/server/auth";
import { body, handle, json } from "@/server/http";

export const dynamic = "force-dynamic";
const Input = z.object({ action: z.enum(["disable", "enable", "revoke_sessions", "reset_mfa"]) });

export const POST = handle(async (req: Request, { params }: { params: Promise<{ id: string }> }) => {
  const ctx = await requireAuth(req, { role: "OWNER", reauth: true });
  const { id } = await params;
  if (!z.uuid().safeParse(id).success) return json({ code: "BAD_REQUEST", message: "Invalid id" }, 400);
  const { action } = await body(req, (v) => Input.parse(v));
  if (action === "revoke_sessions") return json({ revoked: await revokeAllSessions(ctx, id) });
  if (action === "reset_mfa") { await resetMfa(ctx, id); return json({ ok: true }); }
  await setUserStatus(ctx, id, action === "disable" ? "DISABLED" : "ACTIVE");
  return json({ ok: true });
});

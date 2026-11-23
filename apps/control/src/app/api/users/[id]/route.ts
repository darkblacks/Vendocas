import { z } from "zod";
import { adminResetLink, requireAuth, resetMfa, revokeAllSessions, setUserStatus } from "@/server/auth";
import { body, handle, json } from "@/server/http";

export const dynamic = "force-dynamic";
const Input = z.object({ action: z.enum(["disable", "enable", "revoke_sessions", "reset_mfa", "reset_link"]) });

export const POST = handle(async (req: Request, { params }: { params: Promise<{ id: string }> }) => {
  const ctx = await requireAuth(req, { role: "OWNER", reauth: true });
  const { id } = await params;
  if (!z.uuid().safeParse(id).success) return json({ code: "BAD_REQUEST", message: "Invalid id" }, 400);
  const { action } = await body(req, (v) => Input.parse(v));
  if (action === "revoke_sessions") return json({ revoked: await revokeAllSessions(ctx, id) });
  if (action === "reset_mfa") { await resetMfa(ctx, id); return json({ ok: true }); }
  if (action === "reset_link") {
    const t = await adminResetLink(ctx, id);
    // token in the URL fragment: never sent to servers, proxies or logs
    return json({ url: `${new URL(req.url).origin}/reset#${t.token}`, expiresAt: t.expiresAt });
  }
  await setUserStatus(ctx, id, action === "disable" ? "DISABLED" : "ACTIVE");
  return json({ ok: true });
});

import { currentSession } from "@/server/auth";
import { handle, json } from "@/server/http";

export const dynamic = "force-dynamic";
export const GET = handle(async (req: Request) => {
  const ctx = await currentSession(req);
  if (!ctx) return json({ code: "UNAUTHENTICATED", message: "Sign in required" }, 401);
  return json({ user: ctx.user, csrfToken: ctx.session.csrfToken, reauthAt: ctx.session.reauthAt, expiresAt: ctx.session.expiresAt });
});

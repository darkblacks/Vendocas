import { recoveryCodesLeft, refreshSession } from "@/server/auth";
import { handle, json, jsonWithCookies } from "@/server/http";

export const dynamic = "force-dynamic";

/** Current user + keep-alive: slides the session, rotates its token when due and re-trusts this browser if needed. */
export const GET = handle(async (req: Request) => {
  const r = await refreshSession(req);
  if (!r) return json({ code: "UNAUTHENTICATED", message: "Sign in required" }, 401);
  const { ctx } = r;
  return jsonWithCookies({ user: ctx.user, csrfToken: ctx.session.csrfToken, reauthAt: ctx.session.reauthAt, expiresAt: ctx.session.expiresAt,
    recoveryCodesLeft: await recoveryCodesLeft(ctx.user.id) }, r.cookies);
});

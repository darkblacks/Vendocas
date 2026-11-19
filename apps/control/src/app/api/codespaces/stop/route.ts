import { requireAuth } from "@/server/auth";
import { stop } from "@/server/codespaces";
import { handle, json } from "@/server/http";
import { rateLimit } from "@/server/rate-limit";

export const dynamic = "force-dynamic";
export const POST = handle(async (req: Request) => {
  const ctx = await requireAuth(req, { role: "ADMIN" });
  await rateLimit(`cs-op:${ctx.user.id}`, 10, 300);
  return json(await stop(ctx));
});

import { clearSessionCookie, logout } from "@/server/auth";
import { handle, json } from "@/server/http";

export const dynamic = "force-dynamic";
export const POST = handle(async (req: Request) => {
  await logout(req);
  return json({ ok: true }, 200, { "Set-Cookie": clearSessionCookie() });
});

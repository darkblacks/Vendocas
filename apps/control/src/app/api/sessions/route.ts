import { requireAuth } from "@/server/auth";
import { sessions } from "@/server/codespaces";
import { handle, json } from "@/server/http";

export const dynamic = "force-dynamic";
export const GET = handle(async (req: Request) => {
  await requireAuth(req, { role: "VIEWER" });
  return json(await sessions(100));
});

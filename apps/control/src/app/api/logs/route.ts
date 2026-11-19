import { requireAuth } from "@/server/auth";
import { handle, json } from "@/server/http";
import { recentLogs } from "@/server/status";

export const dynamic = "force-dynamic";
export const GET = handle(async (req: Request) => {
  await requireAuth(req, { role: "VIEWER" });
  return json(await recentLogs(200));
});

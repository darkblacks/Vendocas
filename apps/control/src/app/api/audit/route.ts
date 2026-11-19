import { requireAuth } from "@/server/auth";
import { handle, json } from "@/server/http";
import { auditEvents } from "@/server/status";

export const dynamic = "force-dynamic";
export const GET = handle(async (req: Request) => {
  await requireAuth(req, { role: "ADMIN" });
  return json(await auditEvents(300));
});

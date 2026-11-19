import { requireAuth } from "@/server/auth";
import { available } from "@/server/codespaces";
import { handle, json } from "@/server/http";

export const dynamic = "force-dynamic";
export const GET = handle(async (req: Request) => {
  await requireAuth(req, { role: "ADMIN" });
  return json(await available());
});

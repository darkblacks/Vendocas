import { requireAuth } from "@/server/auth";
import { githubToken } from "@/server/codespaces";
import { codespacesUsage, whoAmI } from "@/server/github";
import { handle, json } from "@/server/http";

export const dynamic = "force-dynamic";
export const GET = handle(async (req: Request) => {
  await requireAuth(req, { role: "VIEWER" });
  const token = await githubToken();
  return json(await codespacesUsage(token, await whoAmI(token)));
});

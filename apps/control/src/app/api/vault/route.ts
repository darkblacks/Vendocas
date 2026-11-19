import { requireAuth } from "@/server/auth";
import { handle, json } from "@/server/http";
import { catalog, list } from "@/server/vault";

export const dynamic = "force-dynamic";
/** Catalog + masked metadata only. No route ever returns decrypted values. */
export const GET = handle(async (req: Request) => {
  await requireAuth(req, { role: "ADMIN" });
  return json({ providers: catalog(), credentials: await list() });
});

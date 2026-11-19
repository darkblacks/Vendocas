import { requireAuth } from "@/server/auth";
import { handle, json } from "@/server/http";
import { rotateEncryptionKeys } from "@/server/vault";

export const dynamic = "force-dynamic";
export const POST = handle(async (req: Request) => json(await rotateEncryptionKeys(await requireAuth(req, { role: "OWNER", reauth: true }))));

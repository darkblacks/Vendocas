import { z } from "zod";
import { requireAuth } from "@/server/auth";
import { handle, json } from "@/server/http";
import { merchant } from "@/server/commercial";

export const dynamic = "force-dynamic";

export const GET = handle(async (req: Request, { params }: { params: Promise<{ id: string }> }) => {
  const ctx = await requireAuth(req, { role: "ADMIN" });
  const target = z.enum(["TEST", "LIVE"]).parse(new URL(req.url).searchParams.get("target") ?? "TEST");
  return json(await merchant(ctx, target, (await params).id));
});

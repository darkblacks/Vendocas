import { z } from "zod";
import { createAdministrator, listUsers, requireAuth } from "@/server/auth";
import { body, handle, json } from "@/server/http";

export const dynamic = "force-dynamic";
export const GET = handle(async (req: Request) => {
  await requireAuth(req, { role: "ADMIN" });
  return json(await listUsers());
});

const Input = z.object({ email: z.email(), displayName: z.string().min(2).max(120), role: z.enum(["ADMIN", "VIEWER"]) });
export const POST = handle(async (req: Request) => {
  const ctx = await requireAuth(req, { role: "OWNER", reauth: true });
  return json(await createAdministrator(ctx, await body(req, (v) => Input.parse(v))), 201);
});

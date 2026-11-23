import { z } from "zod";
import { resetPassword } from "@/server/auth";
import { body, handle, json } from "@/server/http";

export const dynamic = "force-dynamic";
const Input = z.object({ token: z.string().min(20).max(200), newPassword: z.string().min(14).max(200) });

/** Consumes a single-use reset token (from an owner-generated link or the owner recovery). */
export const POST = handle(async (req: Request) => {
  const i = await body(req, (v) => Input.parse(v));
  return json(await resetPassword(req, i.token, i.newPassword));
});

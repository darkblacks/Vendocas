import { z } from "zod";
import { loginMfa } from "@/server/auth";
import { body, handle, json } from "@/server/http";

export const dynamic = "force-dynamic";
const Input = z.object({ challenge: z.string().min(20).max(200), code: z.string().min(6).max(12) });

export const POST = handle(async (req: Request) => {
  const i = await body(req, (v) => Input.parse(v));
  const s = await loginMfa(req, i.challenge, i.code);
  return json({ csrfToken: s.csrfToken }, 200, { "Set-Cookie": s.cookie });
});

import { z } from "zod";
import { completeEnrollment } from "@/server/auth";
import { body, handle, json } from "@/server/http";

export const dynamic = "force-dynamic";
const Input = z.object({ enrollToken: z.string().min(20).max(200), code: z.string().regex(/^\d{6}$/) });

export const POST = handle(async (req: Request) => {
  const i = await body(req, (v) => Input.parse(v));
  const r = await completeEnrollment(req, i.enrollToken, i.code);
  return json({ recoveryCodes: r.recoveryCodes, csrfToken: r.csrfToken }, 200, { "Set-Cookie": r.cookie });
});

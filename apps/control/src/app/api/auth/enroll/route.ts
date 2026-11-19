import { z } from "zod";
import { completeEnrollment } from "@/server/auth";
import { body, handle } from "@/server/http";
import { clearSetupCookie } from "@/server/install";

export const dynamic = "force-dynamic";
const Input = z.object({ enrollToken: z.string().min(20).max(200), code: z.string().regex(/^\d{6}$/) });

export const POST = handle(async (req: Request) => {
  const i = await body(req, (v) => Input.parse(v));
  const r = await completeEnrollment(req, i.enrollToken, i.code);
  const headers = new Headers({ "Content-Type": "application/json", "Cache-Control": "no-store" });
  headers.append("Set-Cookie", r.cookie);
  headers.append("Set-Cookie", clearSetupCookie()); // the installation session ends with the first real session
  return new Response(JSON.stringify({ recoveryCodes: r.recoveryCodes, csrfToken: r.csrfToken }), { status: 200, headers });
});

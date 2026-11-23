import { z } from "zod";
import { login } from "@/server/auth";
import { body, handle, json, jsonWithCookies } from "@/server/http";

export const dynamic = "force-dynamic";
const Input = z.object({ email: z.email(), password: z.string().min(1).max(200) });

/** E-mail + password. On a trusted device this completes the sign-in; on a new device it asks for the second factor once. */
export const POST = handle(async (req: Request) => {
  const i = await body(req, (v) => Input.parse(v));
  const r = await login(req, i.email, i.password);
  if (r.step === "DONE") return jsonWithCookies({ step: "DONE", csrfToken: r.csrfToken }, r.cookies);
  return json(r.step === "ENROLL" ? { step: "ENROLL", enroll: r.enroll } : { step: "MFA", challenge: r.challenge });
});

import { z } from "zod";
import { login } from "@/server/auth";
import { body, handle, json } from "@/server/http";

export const dynamic = "force-dynamic";
const Input = z.object({ email: z.email(), password: z.string().min(1).max(200) });

export const POST = handle(async (req: Request) => {
  const i = await body(req, (v) => Input.parse(v));
  const r = await login(req, i.email, i.password);
  return json(r.enroll ? { step: "ENROLL", enroll: r.enroll } : { step: "MFA", challenge: r.challenge });
});

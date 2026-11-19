import { z } from "zod";
import { handle, json, body } from "@/server/http";
import { startSetupSession } from "@/server/install";

export const dynamic = "force-dynamic";
const Input = z.object({ email: z.string().min(3).max(320), key: z.string().min(1).max(500) });

/** Step 1: CONTROL_SETUP_EMAIL + CONTROL_SETUP_KEY → short-lived signed setup session cookie. */
export const POST = handle(async (req: Request) => {
  const r = await startSetupSession(req, await body(req, (v) => Input.parse(v)));
  return json({ stage: r.state.stage, database: r.state.database }, 200, { "Set-Cookie": r.cookie });
});

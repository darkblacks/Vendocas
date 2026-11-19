import { z } from "zod";
import { body, handle, json } from "@/server/http";
import { createOwner } from "@/server/install";

export const dynamic = "force-dynamic";
const Input = z.object({ displayName: z.string().min(2).max(120), password: z.string().min(14).max(200) });

/** Step 3: owner account (PENDING_MFA). Privileges exist only after the MFA enrollment completes. */
export const POST = handle(async (req: Request) => json(await createOwner(req, await body(req, (v) => Input.parse(v)))));

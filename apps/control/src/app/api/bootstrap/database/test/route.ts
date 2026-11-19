import { z } from "zod";
import { body, handle, json } from "@/server/http";
import { testDatabase } from "@/server/install";

export const dynamic = "force-dynamic";
const Input = z.object({ connectionString: z.string().min(1).max(4096) });

/** "Testar conexão": read-only probe of the pasted connection string. Nothing is stored. */
export const POST = handle(async (req: Request) => json(await testDatabase(req, (await body(req, (v) => Input.parse(v))).connectionString)));

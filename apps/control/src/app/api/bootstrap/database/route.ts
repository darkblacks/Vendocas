import { z } from "zod";
import { body, handle, json } from "@/server/http";
import { saveDatabase } from "@/server/install";

export const dynamic = "force-dynamic";
const Input = z.object({ connectionString: z.string().min(1).max(4096) });

/** "Salvar e continuar": test, prepare the schema and store the connection string encrypted. */
export const POST = handle(async (req: Request) => json(await saveDatabase(req, (await body(req, (v) => Input.parse(v))).connectionString)));

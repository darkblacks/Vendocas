import { z } from "zod";
import { bootstrapDatabase } from "@/server/auth";
import { body, handle, json } from "@/server/http";

export const dynamic = "force-dynamic";

const Input = z.object({ token: z.string().min(16).max(200), connectionString: z.string().min(1).max(4096) });

/** Bootstrap step 1: test and store the PostgreSQL connection string (encrypted, external store). */
export const POST = handle(async (req: Request) => json(await bootstrapDatabase(req, await body(req, (v) => Input.parse(v)))));

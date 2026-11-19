import { z } from "zod";
import { bootstrapStart, bootstrapStatus } from "@/server/auth";
import { body, handle, json } from "@/server/http";

export const dynamic = "force-dynamic";

export const GET = handle(async () => json(await bootstrapStatus()));

const Input = z.object({ token: z.string().min(16).max(200), email: z.email(), displayName: z.string().min(2).max(120), password: z.string().min(14).max(200) });

export const POST = handle(async (req: Request) => json(await bootstrapStart(req, await body(req, (v) => Input.parse(v)))));

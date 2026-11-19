import { z } from "zod";
import { requireAuth } from "@/server/auth";
import { body, handle, json } from "@/server/http";
import { historyFor, revoke, save, setDisabled, test } from "@/server/vault";

export const dynamic = "force-dynamic";
type P = { params: Promise<{ provider: string }> };
const Env = z.enum(["TEST", "LIVE"]);
const providerId = async (params: P["params"]) => z.string().regex(/^[A-Z0-9_]{2,40}$/).parse((await params).provider);

export const GET = handle(async (req: Request, { params }: P) => {
  await requireAuth(req, { role: "ADMIN" });
  return json(await historyFor(await providerId(params)));
});

const SaveInput = z.object({ environment: Env, values: z.record(z.string(), z.string().max(4096)) });
export const PUT = handle(async (req: Request, { params }: P) => {
  const ctx = await requireAuth(req, { role: "ADMIN", reauth: true });
  const i = await body(req, (v) => SaveInput.parse(v));
  return json(await save(ctx, await providerId(params), i.environment, i.values));
});

const ActionInput = z.object({ environment: Env, action: z.enum(["test", "disable", "revoke"]) });
export const POST = handle(async (req: Request, { params }: P) => {
  const i = await body(req.clone(), (v) => ActionInput.parse(v));
  const ctx = await requireAuth(req, { role: "ADMIN", reauth: i.action !== "test" });
  const id = await providerId(params);
  if (i.action === "test") return json(await test(ctx, id, i.environment));
  if (i.action === "disable") { await setDisabled(ctx, id, i.environment); return json({ ok: true }); }
  return json(await revoke(ctx, id, i.environment));
});

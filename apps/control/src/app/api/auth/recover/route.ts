import { z } from "zod";
import { createPasswordReset } from "@/server/auth";
import { db } from "@/server/db";
import { HttpError, body, clientIp, handle, json } from "@/server/http";
import { verifyInstallCredentials } from "@/server/install";
import { audit } from "@/server/audit";

export const dynamic = "force-dynamic";
const Input = z.object({ email: z.string().min(3).max(320), key: z.string().min(1).max(500) });

/**
 * Owner emergency recovery (lost password and/or authenticator): CONTROL_SETUP_EMAIL + CONTROL_SETUP_KEY, the same
 * check as the installation. Returns a single-use token valid 15 minutes that resets the password AND the MFA
 * (re-enrollment on next sign-in) and signs the owner out everywhere. Not part of the normal sign-in.
 */
export const POST = handle(async (req: Request) => {
  const email = await verifyInstallCredentials(req, await body(req, (v) => Input.parse(v)));
  const p = await db();
  const owner = (await p.query("SELECT id FROM cc_users WHERE role = 'OWNER' AND email = $1 AND status <> 'DISABLED'", [email])).rows[0];
  if (!owner) throw new HttpError(404, "OWNER_NOT_FOUND", "No active owner account with the installation e-mail");
  const t = await createPasswordReset(owner.id, { resetMfa: true, minutes: 15 });
  await audit({ id: owner.id, label: `recovery:${email}`, ip: clientIp(req) }, "auth.owner_recovery_started", "SUCCESS", owner.id);
  return json({ token: t.token, expiresAt: t.expiresAt });
});

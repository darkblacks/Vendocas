import type { ReactNode } from "react";
import { cookies, headers } from "next/headers";
import { redirect } from "next/navigation";
import { currentSession } from "@/server/auth";
import { ConfigError } from "@/server/env";
import { storedDatabaseConfig } from "@/server/database-config";
import { Shell } from "@/components/shell";

export const dynamic = "force-dynamic";

/** Every console page is gated server-side by a database-validated session (not by URL obscurity). */
export default async function ConsoleLayout({ children }: { children: ReactNode }) {
  const cookie = (await cookies()).toString();
  const h = await headers();
  let ctx = null;
  let notConfigured = false;
  try {
    ctx = await currentSession(new Request("http://control.internal/", { headers: { cookie, "user-agent": h.get("user-agent") ?? "" } }));
  } catch (e) {
    if (!(e instanceof ConfigError && e.code === "DATABASE_NOT_CONFIGURED")) redirect("/login?error=unavailable");
    notConfigured = true;
  }
  if (!ctx && !notConfigured) {
    // No session: send a brand-new installation to the setup instead of a login that cannot work yet.
    const stored = process.env.CONTROL_DATABASE_URL?.trim() ? true : await storedDatabaseConfig().then((c) => !!c).catch(() => true);
    notConfigured = !stored;
  }
  if (notConfigured) redirect("/bootstrap");
  if (!ctx) redirect("/login");
  return <Shell user={{ email: ctx.user.email, name: ctx.user.displayName, role: ctx.user.role }} csrfToken={ctx.session.csrfToken}>{children}</Shell>;
}

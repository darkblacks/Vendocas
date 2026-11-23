import type { ReactNode } from "react";
import { cookies, headers } from "next/headers";
import { redirect } from "next/navigation";
import { currentSession } from "@/server/auth";
import { ConfigError } from "@/server/env";
import { storedDatabaseConfig } from "@/server/database-config";
import { Shell } from "@/components/shell";
import { TemporarilyUnavailable } from "@/components/unavailable";

export const dynamic = "force-dynamic";

/** Every console page is gated server-side by a database-validated session (not by URL obscurity). */
export default async function ConsoleLayout({ children }: { children: ReactNode }) {
  const cookie = (await cookies()).toString();
  const h = await headers();
  let ctx = null;
  let notConfigured = false;
  let unavailable = false;
  try {
    ctx = await currentSession(new Request("http://control.internal/", { headers: { cookie, "user-agent": h.get("user-agent") ?? "" } }));
  } catch (e) {
    // Only "no installation" leaves the page. A slow database (e.g. Neon waking up) or a timeout is NOT a logout:
    // the session cookie is kept and the page offers to retry.
    if (e instanceof ConfigError && e.code === "DATABASE_NOT_CONFIGURED") notConfigured = true;
    else unavailable = true;
  }
  if (unavailable) return <TemporarilyUnavailable />;
  if (!ctx && !notConfigured) {
    // No session: send a brand-new installation to the setup instead of a login that cannot work yet.
    const stored = process.env.CONTROL_DATABASE_URL?.trim() ? true : await storedDatabaseConfig().then((c) => !!c).catch(() => true);
    notConfigured = !stored;
  }
  if (notConfigured) redirect("/bootstrap");
  if (!ctx) redirect("/login");
  return <Shell user={{ email: ctx.user.email, name: ctx.user.displayName, role: ctx.user.role }} csrfToken={ctx.session.csrfToken}>{children}</Shell>;
}

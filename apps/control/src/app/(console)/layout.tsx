import type { ReactNode } from "react";
import { cookies, headers } from "next/headers";
import { redirect } from "next/navigation";
import { currentSession } from "@/server/auth";
import { Shell } from "@/components/shell";

export const dynamic = "force-dynamic";

/** Every console page is gated server-side by a database-validated session (not by URL obscurity). */
export default async function ConsoleLayout({ children }: { children: ReactNode }) {
  const cookie = (await cookies()).toString();
  const h = await headers();
  let ctx = null;
  try {
    ctx = await currentSession(new Request("http://control.internal/", { headers: { cookie, "user-agent": h.get("user-agent") ?? "" } }));
  } catch {
    redirect("/login?error=unavailable");
  }
  if (!ctx) redirect("/login");
  return <Shell user={{ email: ctx.user.email, name: ctx.user.displayName, role: ctx.user.role }} csrfToken={ctx.session.csrfToken}>{children}</Shell>;
}

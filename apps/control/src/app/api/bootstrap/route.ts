import { handle, json } from "@/server/http";
import { installInfo } from "@/server/install";

export const dynamic = "force-dynamic";

/** Instant: no database, no config store, no secrets — only whether the two Netlify variables exist. */
export const GET = handle(async () => json(installInfo()));

import { requireAuth } from "@/server/auth";
import { githubToken, settings } from "@/server/codespaces";
import { latestCommit, workflowRuns } from "@/server/github";
import { handle, json } from "@/server/http";

export const dynamic = "force-dynamic";
export const GET = handle(async (req: Request) => {
  await requireAuth(req, { role: "VIEWER" });
  const s = await settings();
  if (!s.repository) return json({ configured: false, reason: "Set the GitHub repository in Settings" });
  const token = await githubToken();
  const [commit, runs] = await Promise.all([latestCommit(token, s.repository), workflowRuns(token, s.repository)]);
  return json({ configured: true, repository: s.repository, mainCommit: commit, runs,
    netlifyDeploys: { web: s.webAppUrl, control: process.env.URL ?? null, enabled: false } });
});

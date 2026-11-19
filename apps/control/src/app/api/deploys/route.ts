import { requireAuth } from "@/server/auth";
import { githubToken } from "@/server/codespaces";
import { env } from "@/server/env";
import { latestCommit, workflowRuns } from "@/server/github";
import { handle, json } from "@/server/http";

export const dynamic = "force-dynamic";
export const GET = handle(async (req: Request) => {
  await requireAuth(req, { role: "VIEWER" });
  const repo = env.githubRepository();
  if (!repo) return json({ configured: false, reason: "CONTROL_GITHUB_REPOSITORY not set" });
  const token = await githubToken();
  const [commit, runs] = await Promise.all([latestCommit(token, repo), workflowRuns(token, repo)]);
  return json({ configured: true, repository: repo, mainCommit: commit, runs,
    netlifyDeploys: { web: process.env.CONTROL_NETLIFY_WEB_URL ?? null, control: process.env.URL ?? null, enabled: false } });
});

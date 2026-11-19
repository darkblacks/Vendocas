#!/usr/bin/env node
// Decides which CI pipelines a change set needs. Pure function + thin CLI so it is unit-tested (detect-changes.test.mjs).
//
//   node scripts/ci/detect-changes.mjs --base <sha> --head <sha>   # uses git diff
//   node scripts/ci/detect-changes.mjs --files a,b,c               # explicit list (tests / debugging)
//
// Writes `web=true|false`, `control=…`, `api=…`, `infra=…` to $GITHUB_OUTPUT when present, and prints JSON.
import { execFileSync } from "node:child_process";
import { appendFileSync } from "node:fs";
import { pathToFileURL } from "node:url";

/** Ordered rules: first match wins. `targets` = pipelines that must run. */
export const RULES = [
  { pattern: /^legacy\//, targets: [] }, // the old ERP is frozen: never builds or deploys anything (integrity checked separately)
  { pattern: /^apps\/web\//, targets: ["web"] },
  { pattern: /^apps\/control\//, targets: ["control"] },
  { pattern: /^apps\/api\//, targets: ["api"] },
  { pattern: /^packages\/design-system\//, targets: ["web", "control"] },
  { pattern: /^packages\/(api-client|tracking-sdk)\//, targets: ["web"] },
  { pattern: /^packages\/contracts\//, targets: ["api", "web"] },
  { pattern: /^(pnpm-lock\.yaml|pnpm-workspace\.yaml|package\.json|\.npmrc|\.nvmrc)$/, targets: ["web", "control"] },
  { pattern: /^infrastructure\/docker\/api\.Dockerfile$/, targets: ["api", "infra"] },
  { pattern: /^infrastructure\/docker\/web\.Dockerfile$/, targets: ["web", "infra"] },
  { pattern: /^scripts\/ci\//, targets: ["infra"] },
  { pattern: /^(infrastructure\/|\.devcontainer\/|scripts\/|docker-compose[^/]*\.ya?ml$)/, targets: ["infra"] },
  // A workflow change can affect any pipeline: run them all (nothing is ever published by CI alone).
  { pattern: /^\.github\/(workflows|actions)\//, targets: ["web", "control", "api", "infra"] },
  { pattern: /(^docs\/|\.md$|^LICENSE)/, targets: [] },
];
export const PIPELINES = ["web", "control", "api", "infra"];

export function detect(files) {
  const out = Object.fromEntries(PIPELINES.map((p) => [p, false]));
  for (const raw of files) {
    const f = raw.trim().replace(/^\.\//, "");
    if (!f) continue;
    const rule = RULES.find((r) => r.pattern.test(f));
    // Unknown paths are treated conservatively: validate infra (cheap) rather than silently skipping.
    for (const t of rule ? rule.targets : ["infra"]) out[t] = true;
  }
  return out;
}

function changedFiles(base, head) {
  const zero = /^0+$/;
  if (!base || zero.test(base)) {
    // First push of a branch / unknown base: run everything.
    return null;
  }
  const out = execFileSync("git", ["diff", "--name-only", `${base}...${head}`], { encoding: "utf8" });
  return out.split("\n").filter(Boolean);
}

function main(argv) {
  const arg = (n) => { const i = argv.indexOf(n); return i >= 0 ? argv[i + 1] : undefined; };
  const list = arg("--files");
  const files = list !== undefined ? list.split(",") : changedFiles(arg("--base"), arg("--head") ?? "HEAD");
  const result = files === null ? Object.fromEntries(PIPELINES.map((p) => [p, true])) : detect(files);
  if (process.env.GITHUB_OUTPUT) appendFileSync(process.env.GITHUB_OUTPUT, PIPELINES.map((p) => `${p}=${result[p]}`).join("\n") + "\n");
  console.log(JSON.stringify({ files: files?.length ?? "all", ...result }));
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) main(process.argv.slice(2));

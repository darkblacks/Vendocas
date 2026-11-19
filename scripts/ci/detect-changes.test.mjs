import { test } from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { detect } from "./detect-changes.mjs";

const only = (...on) => Object.fromEntries(["web", "control", "api", "infra"].map((p) => [p, on.includes(p)]));

test("web-only change builds only web", () => {
  assert.deepEqual(detect(["apps/web/src/app/page.tsx"]), only("web"));
});
test("control-only change builds only the Control Center", () => {
  assert.deepEqual(detect(["apps/control/src/server/vault.ts", "apps/control/netlify.toml"]), only("control"));
});
test("backend-only change does not touch the frontends", () => {
  assert.deepEqual(detect(["apps/api/src/main/java/com/vendocas/App.java", "apps/api/pom.xml"]), only("api"));
});
test("shared design system rebuilds both frontends, not the API", () => {
  assert.deepEqual(detect(["packages/design-system/src/tokens.css"]), only("web", "control"));
});
test("API client / tracking SDK only affect web", () => {
  assert.deepEqual(detect(["packages/api-client/src/index.ts", "packages/tracking-sdk/src/index.ts"]), only("web"));
});
test("lockfile change rebuilds node apps", () => {
  assert.deepEqual(detect(["pnpm-lock.yaml"]), only("web", "control"));
});
test("legacy ERP changes trigger no build or deploy", () => {
  assert.deepEqual(detect(["legacy/erp-financeiro-local/index.html"]), only());
});
test("docs-only change triggers nothing", () => {
  assert.deepEqual(detect(["docs/security.md", "README.md"]), only());
});
test("devcontainer and compose changes validate infra", () => {
  assert.deepEqual(detect([".devcontainer/devcontainer.json", "docker-compose.yml"]), only("infra"));
});
test("Dockerfile changes also rebuild their app", () => {
  assert.deepEqual(detect(["infrastructure/docker/api.Dockerfile"]), only("api", "infra"));
});
test("workflow changes run every pipeline", () => {
  assert.deepEqual(detect([".github/workflows/web.yml"]), only("web", "control", "api", "infra"));
});
test("unknown paths fall back to infra validation", () => {
  assert.deepEqual(detect(["something-new.txt"]), only("infra"));
});

test("CLI writes GitHub outputs", () => {
  const out = join(mkdtempSync(join(tmpdir(), "dc-")), "out");
  execFileSync("node", [new URL("./detect-changes.mjs", import.meta.url).pathname, "--files", "apps/control/a.ts"], { env: { ...process.env, GITHUB_OUTPUT: out } });
  assert.equal(readFileSync(out, "utf8"), "web=false\ncontrol=true\napi=false\ninfra=false\n");
});

test("workflows never publish without the explicit opt-in variables", () => {
  const dir = new URL("../../.github/workflows/", import.meta.url);
  for (const [file, gate] of [["web.yml", "ENABLE_NETLIFY_WEB_DEPLOY"], ["control.yml", "ENABLE_NETLIFY_CONTROL_DEPLOY"], ["api.yml", "ENABLE_API_HOMOLOG_DEPLOY"]]) {
    const y = readFileSync(new URL(file, dir), "utf8");
    const deployJobs = y.split(/\n  (?=[a-z-]+:\n)/).filter((j) => /^deploy/.test(j));
    assert.ok(deployJobs.length >= 1, `${file} has a deploy job`);
    for (const j of deployJobs) assert.match(j, new RegExp(`vars\\.${gate} == 'true'`), `${file} deploy gated by ${gate}`);
  }
});

test("workflows contain no inline secrets", () => {
  const dir = new URL("../../.github/workflows/", import.meta.url);
  for (const f of ["ci.yml", "web.yml", "control.yml", "api.yml"]) {
    const y = readFileSync(new URL(f, dir), "utf8");
    assert.doesNotMatch(y, /(sk_(live|test)_[A-Za-z0-9]{10}|ghp_[A-Za-z0-9]{20}|github_pat_[A-Za-z0-9_]{20}|AKIA[0-9A-Z]{16}|EAA[A-Za-z0-9]{20})/, f);
  }
});

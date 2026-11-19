import { randomBytes } from "node:crypto";

// Test-only configuration. A real installation needs ONLY CONTROL_SETUP_EMAIL and CONTROL_SETUP_KEY on Netlify.
// CONTROL_DATABASE_URL is the optional override; most suites use it to skip the database step (bootstrap-database
// tests remove it and exercise the pasted-connection-string flow instead).
process.env.CONTROL_DATABASE_URL ??= process.env.TEST_DATABASE_URL ?? "postgres://vendocas:vendocas@localhost:5432/control_test";
process.env.CONTROL_SETUP_EMAIL = "owner@example.com";
process.env.CONTROL_SETUP_KEY = randomBytes(32).toString("base64url");
process.env.CONTROL_GITHUB_API_URL = "https://github.fake";
for (const legacy of ["CONTROL_MASTER_KEYS", "CONTROL_ACTIVE_KEY_VERSION", "CONTROL_BOOTSTRAP_TOKEN", "CONTROL_BOOTSTRAP_EMAIL", "CONTROL_GITHUB_REPOSITORY", "CONTROL_PUBLIC_ORIGIN"]) delete process.env[legacy];

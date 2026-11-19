import { randomBytes } from "node:crypto";

// Test-only configuration. Real deployments read these from Netlify environment variables.
process.env.CONTROL_DATABASE_URL ??= process.env.TEST_DATABASE_URL ?? "postgres://vendocas:vendocas@localhost:5432/control_test";
process.env.CONTROL_MASTER_KEYS = `v1:${randomBytes(32).toString("base64")}`;
process.env.CONTROL_ACTIVE_KEY_VERSION = "v1";
process.env.CONTROL_BOOTSTRAP_TOKEN = "test-bootstrap-token-0123456789abcdef";
process.env.CONTROL_BOOTSTRAP_EMAIL = "owner@example.com";
process.env.CONTROL_GITHUB_REPOSITORY = "darkblacks/Vendocas";
process.env.CONTROL_PUBLIC_ORIGIN = "https://control.test";
process.env.CONTROL_GITHUB_API_URL = "https://github.fake";

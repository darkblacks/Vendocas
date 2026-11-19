import { defineConfig } from "@playwright/test";

/**
 * E2E against a real stack: API (with MAIL_MODE=log and its log file in E2E_API_LOG) + `next start`.
 * See docs/testing.md. No provider is mocked: provider-dependent steps assert the honest NOT_CONFIGURED state.
 */
export default defineConfig({
  testDir: "tests/e2e",
  timeout: 60_000,
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:3000",
    trace: "off",
    // Optional: reuse a preinstalled Chromium (e.g. CI images) instead of `playwright install`.
    launchOptions: process.env.PW_CHROMIUM_PATH ? { executablePath: process.env.PW_CHROMIUM_PATH } : {},
  },
  reporter: [["list"]],
});

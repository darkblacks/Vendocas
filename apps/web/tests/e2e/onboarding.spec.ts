import { expect, test } from "@playwright/test";
import { readFileSync } from "node:fs";

const LOG = process.env.E2E_API_LOG ?? "";

function latestToken(kind: "verify-email" | "reset-password"): string {
  const log = readFileSync(LOG, "utf8");
  const all = [...log.matchAll(new RegExp(`${kind}\\?token=([A-Za-z0-9_-]+)`, "g"))];
  return all.at(-1)![1]!;
}

test("public site in Portuguese shows pricing from the API", async ({ page }) => {
  await page.goto("/pt");
  await expect(page.getByRole("heading", { level: 1 })).toContainText("Traga clientes de volta");
  await expect(page.getByText("Complete Trio")).toBeVisible();
  await expect(page.getByText("US$ 18,00").or(page.getByText("$18.00"))).toBeVisible();
});

test("sign up → verify → sign in → set up a Brazilian business → see billing and contacts", async ({ page }) => {
  test.skip(!LOG, "E2E_API_LOG not set");
  const email = `e2e-${Date.now()}@example.com`;
  await page.goto("/en/register");
  await page.getByLabel("Full name").fill("E2E Owner");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password").fill("E2e-Strong-Passw0rd");
  await page.getByRole("button", { name: "Create your account" }).click();
  await expect(page.getByText("Check your inbox")).toBeVisible();

  await page.goto(`/en/verify-email?token=${latestToken("verify-email")}`);
  await expect(page.getByText("Email confirmed")).toBeVisible();

  await page.goto("/en/login");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password").fill("E2e-Strong-Passw0rd");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/en\/app\/setup/);

  await page.getByLabel("Legal name").fill("E2E Comércio LTDA");
  await page.getByLabel("Trade name").fill("E2E Store");
  await page.getByLabel("Tax ID", { exact: true }).fill("11.222.333/0001-81");
  await page.getByLabel(/I accept/).check();
  await page.getByRole("button", { name: "Continue" }).click();
  await expect(page).toHaveURL(/\/en\/app\/billing/);
  await expect(page.getByRole("heading", { name: "Billing" })).toBeVisible();

  await page.goto("/en/app/contacts");
  await page.getByRole("button", { name: "Add customer" }).click();
  await page.getByLabel("Name").fill("Maria Teste");
  await page.getByLabel("Email").fill("maria@example.com");
  await page.getByLabel("Phone").fill("+55 11 98765-4321");
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.getByRole("link", { name: "Maria Teste" })).toBeVisible();

  await page.goto("/en/app/automations/new");
  await page.getByRole("button", { name: "Simulate" }).click();
  await expect(page.getByText("WOULD_SEND".toLowerCase().replaceAll("_", " ")).first()).toBeVisible();
});

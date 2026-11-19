import { defineRouting } from "next-intl/routing";

/** Public pages ship in pt/en/es. fr/de are wired structurally and fall back to English strings until translated. */
export const routing = defineRouting({
  locales: ["en", "pt", "es", "fr", "de"],
  defaultLocale: "en",
  localePrefix: "always",
});

export type Locale = (typeof routing.locales)[number];
export const FULLY_TRANSLATED: Locale[] = ["en", "pt", "es"];

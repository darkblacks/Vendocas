import { defineRouting } from "next-intl/routing";
import { LANGUAGES } from "./languages";

/** Six languages, all fully translated: pt, en, es, fr, de, it. */
export const routing = defineRouting({
  locales: ["en", "pt", "es", "fr", "de", "it"],
  defaultLocale: "en",
  localePrefix: "always",
});

export type Locale = (typeof routing.locales)[number];
export const FULLY_TRANSLATED: Locale[] = [...LANGUAGES];

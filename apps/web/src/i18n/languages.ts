/**
 * The platform's six languages and the country → language rule (same as the API's Languages.java).
 * No regional variants: Brazil and Portugal → pt; Spain and Latin America → es; France → fr; Germany/Austria → de;
 * Italy → it; US, Canada, UK, Ireland and every other country → en.
 */
export const LANGUAGES = ["pt", "en", "es", "fr", "de", "it"] as const;
export type Language = (typeof LANGUAGES)[number];

export const LANGUAGE_NAMES: Record<Language, string> = {
  pt: "Português", en: "English", es: "Español", fr: "Français", de: "Deutsch", it: "Italiano",
};

const BY_COUNTRY: Record<string, Language> = {
  BR: "pt", PT: "pt",
  ES: "es", MX: "es", GT: "es", SV: "es", HN: "es", NI: "es", CR: "es", PA: "es", CU: "es", DO: "es", PR: "es",
  CO: "es", VE: "es", EC: "es", PE: "es", BO: "es", CL: "es", AR: "es", UY: "es", PY: "es",
  FR: "fr", GF: "fr", MC: "fr",
  DE: "de", AT: "de",
  IT: "it",
};

export function languageForCountry(country?: string | null): Language {
  return (country && BY_COUNTRY[country.trim().toUpperCase()]) || "en";
}

/** "pt-BR" → "pt"; undefined when it is not one of the six. */
export function languageOf(locale?: string | null): Language | undefined {
  const l = locale?.trim().replace("_", "-").split("-")[0]?.toLowerCase();
  return (LANGUAGES as readonly string[]).includes(l ?? "") ? (l as Language) : undefined;
}

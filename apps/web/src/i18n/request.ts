import { getRequestConfig } from "next-intl/server";
import { hasLocale } from "next-intl";
import { routing } from "./routing";
import en from "../../messages/en.json";

type Messages = Record<string, unknown>;

function deepMerge(base: Messages, over: Messages): Messages {
  const out: Messages = { ...base };
  for (const [k, v] of Object.entries(over)) {
    out[k] = v && typeof v === "object" && !Array.isArray(v) ? deepMerge((base[k] as Messages) ?? {}, v as Messages) : v;
  }
  return out;
}

export default getRequestConfig(async ({ requestLocale }) => {
  const requested = await requestLocale;
  const locale = hasLocale(routing.locales, requested) ? requested : routing.defaultLocale;
  let messages: Messages = en;
  if (locale !== "en") {
    try {
      messages = deepMerge(en, (await import(`../../messages/${locale}.json`)).default as Messages);
    } catch {
      messages = en; // structural locale without translations yet
    }
  }
  return { locale, messages };
});

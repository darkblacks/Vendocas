import { describe, expect, it } from "vitest";
import en from "../../messages/en.json";
import pt from "../../messages/pt.json";
import es from "../../messages/es.json";
import fr from "../../messages/fr.json";
import de from "../../messages/de.json";
import itMsgs from "../../messages/it.json";
import { routing } from "@/i18n/routing";
import { LANGUAGES, languageForCountry, languageOf } from "@/i18n/languages";

type Tree = { [k: string]: string | Tree };
const keys = (t: Tree, p = ""): string[] =>
  Object.entries(t).flatMap(([k, v]) => (typeof v === "string" ? [p + k] : keys(v, `${p}${k}.`)));
const placeholders = (s: string) => [...new Set([...s.matchAll(/\{(\w+)(?=[,}])/g)].map((m) => m[1]))].sort();
const flat = (t: Tree) => Object.fromEntries(keys(t).map((k) => [k, k.split(".").reduce<unknown>((o, x) => (o as Tree)[x], t) as string]));

const files: Record<string, Tree> = { en, pt, es, fr, de, it: itMsgs };

describe("six languages", () => {
  it("routing offers exactly pt, en, es, fr, de, it", () => {
    expect([...routing.locales].sort()).toEqual([...LANGUAGES].sort());
  });

  for (const [lang, tree] of Object.entries(files)) {
    it(`${lang} has every key of en, with the same placeholders`, () => {
      const base = flat(en as Tree), other = flat(tree);
      expect(Object.keys(other).sort()).toEqual(Object.keys(base).sort());
      for (const k of Object.keys(base)) expect(placeholders(other[k]!), `${lang}:${k}`).toEqual(placeholders(base[k]!));
    });
  }

  it("one language per country", () => {
    expect(languageForCountry("CA")).toBe("en");
    expect(languageForCountry("US")).toBe("en");
    expect(languageForCountry("MX")).toBe("es");
    expect(languageForCountry("PT")).toBe("pt");
    expect(languageForCountry("BR")).toBe("pt");
    expect(languageForCountry("FR")).toBe("fr");
    expect(languageForCountry("DE")).toBe("de");
    expect(languageForCountry("ES")).toBe("es");
    expect(languageForCountry("GB")).toBe("en");
    expect(languageForCountry("IE")).toBe("en");
    expect(languageForCountry("IT")).toBe("it");
    expect(languageOf("pt-BR")).toBe("pt");
    expect(languageOf("ja")).toBeUndefined();
  });
});

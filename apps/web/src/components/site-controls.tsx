"use client";
import { useTheme } from "next-themes";
import { useLocale } from "next-intl";
import { Moon, Sun, Languages } from "lucide-react";
import { usePathname, useRouter } from "@/i18n/navigation";
import { routing } from "@/i18n/routing";
import { LANGUAGE_NAMES, type Language } from "@/i18n/languages";
import { Button } from "@/components/ui";


export function ThemeToggle() {
  const { resolvedTheme, setTheme } = useTheme();
  return (
    <Button variant="ghost" size="icon" aria-label="Toggle theme" onClick={() => setTheme(resolvedTheme === "dark" ? "light" : "dark")}>
      <Sun className="h-4 w-4 dark:hidden" /><Moon className="hidden h-4 w-4 dark:block" />
    </Button>
  );
}

export function LocaleSwitcher() {
  const locale = useLocale();
  const router = useRouter();
  const pathname = usePathname();
  return (
    <label className="inline-flex items-center gap-1 text-sm">
      <Languages className="h-4 w-4 text-muted-foreground" aria-hidden />
      <span className="sr-only">Language</span>
      <select className="bg-transparent text-sm" value={locale} onChange={(e) => router.replace(pathname, { locale: e.target.value })}>
        {routing.locales.map((l) => <option key={l} value={l}>{LANGUAGE_NAMES[l as Language]}</option>)}
      </select>
    </label>
  );
}

import type { ReactNode } from "react";
import { getTranslations } from "next-intl/server";
import { Link } from "@/i18n/navigation";
import { LocaleSwitcher, ThemeToggle } from "@/components/site-controls";

export default async function MarketingLayout({ children }: { children: ReactNode }) {
  const t = await getTranslations();
  return (
    <div className="flex min-h-screen flex-col">
      <header className="sticky top-0 z-30 border-b border-border bg-background/90 backdrop-blur">
        <div className="mx-auto flex h-16 max-w-6xl items-center justify-between gap-4 px-4">
          <Link href="/" className="text-lg font-bold tracking-tight">Vendocas</Link>
          <nav className="hidden items-center gap-6 text-sm md:flex">
            <a href="#how" className="hover:text-primary">{t("nav.features")}</a>
            <a href="#pricing" className="hover:text-primary">{t("nav.pricing")}</a>
            <a href="#faq" className="hover:text-primary">{t("nav.faq")}</a>
          </nav>
          <div className="flex items-center gap-2">
            <LocaleSwitcher />
            <ThemeToggle />
            <Link href="/login" className="hidden text-sm font-medium sm:inline">{t("nav.login")}</Link>
            <Link href="/register" className="rounded-lg bg-primary px-3 py-2 text-sm font-medium text-primary-foreground">{t("nav.signup")}</Link>
          </div>
        </div>
      </header>
      <main className="flex-1">{children}</main>
      <footer className="border-t border-border py-10 text-sm text-muted-foreground">
        <div className="mx-auto flex max-w-6xl flex-col gap-4 px-4 sm:flex-row sm:justify-between">
          <p>© {new Date().getFullYear()} Vendocas. {t("footer.rights")}</p>
          <nav className="flex flex-wrap gap-4">
            <Link href="/legal/terms">{t("footer.terms")}</Link>
            <Link href="/legal/privacy">{t("footer.privacy")}</Link>
            <Link href="/legal/cancellation">{t("footer.cancellation")}</Link>
            <Link href="/support">{t("footer.support")}</Link>
          </nav>
        </div>
      </footer>
    </div>
  );
}

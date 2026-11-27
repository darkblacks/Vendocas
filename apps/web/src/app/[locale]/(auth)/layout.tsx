import type { ReactNode } from "react";
import { Link } from "@/i18n/navigation";
import { LocaleSwitcher, ThemeToggle } from "@/components/site-controls";
import { Logo } from "@/components/brand";

export default function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <div className="flex min-h-screen flex-col">
      <header className="flex h-16 items-center justify-between px-4">
        <Link href="/" aria-label="Vendocas"><Logo size={30} /></Link>
        <div className="flex items-center gap-2"><LocaleSwitcher /><ThemeToggle /></div>
      </header>
      <main className="flex flex-1 items-start justify-center px-4 py-10">
        <div className="shadow-glow w-full max-w-md rounded-2xl border border-border bg-card p-6">{children}</div>
      </main>
    </div>
  );
}

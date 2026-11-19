import type { ReactNode } from "react";
import { Link } from "@/i18n/navigation";
import { LocaleSwitcher, ThemeToggle } from "@/components/site-controls";

export default function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <div className="flex min-h-screen flex-col">
      <header className="flex h-16 items-center justify-between px-4">
        <Link href="/" className="text-lg font-bold">Vendocas</Link>
        <div className="flex items-center gap-2"><LocaleSwitcher /><ThemeToggle /></div>
      </header>
      <main className="flex flex-1 items-start justify-center px-4 py-10">
        <div className="w-full max-w-md rounded-lg border border-border bg-card p-6 shadow-sm">{children}</div>
      </main>
    </div>
  );
}

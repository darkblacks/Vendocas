"use client";
import { useEffect, useRef, useState, type ReactNode } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { Activity, FileClock, GitBranch, KeyRound, LayoutDashboard, Menu, ScrollText, Server, Settings, ShieldCheck, SlidersHorizontal, X, Globe, Store } from "lucide-react";
import { call, message, onReauthRequired, setCsrf } from "@/lib/client";
import { Alert, Button, Field, Input, cn } from "./ui";

const NAV = [
  { href: "/", label: "Dashboard", icon: LayoutDashboard },
  { href: "/server", label: "Servidor / Codespaces", icon: Server },
  { href: "/credentials", label: "Credenciais e Integrações", icon: KeyRound },
  { href: "/merchants", label: "Lojistas", icon: Store },
  { href: "/pricing", label: "Mercados e preços", icon: Globe },
  { href: "/platform", label: "Pagamentos e fiscal", icon: SlidersHorizontal },
  { href: "/deploys", label: "Deploys", icon: GitBranch },
  { href: "/logs", label: "Logs", icon: ScrollText },
  { href: "/sessions", label: "Histórico de sessões", icon: FileClock },
  { href: "/settings", label: "Configurações", icon: Settings },
  { href: "/security", label: "Segurança e auditoria", icon: ShieldCheck },
];

export function Shell({ user, csrfToken, children }: { user: { email: string; name: string; role: string }; csrfToken: string; children: ReactNode }) {
  const pathname = usePathname();
  const [open, setOpen] = useState(false);
  const [reauth, setReauth] = useState(false);
  const resolver = useRef<((ok: boolean) => void) | null>(null);
  useEffect(() => { setCsrf(csrfToken); }, [csrfToken]);
  useScrollLock(open || reauth);
  useSessionKeepAlive();
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") setOpen(false); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open]);
  useEffect(() => {
    onReauthRequired(() => new Promise<boolean>((res) => { resolver.current = res; setReauth(true); }));
    return () => onReauthRequired(null);
  }, []);
  const nav = (
    <nav className="flex flex-col gap-1 p-3">
      {NAV.map(({ href, label, icon: Icon }) => (
        <Link key={href} href={href} onClick={() => setOpen(false)}
          className={cn("flex items-center gap-3 rounded-lg px-3 py-2 text-sm", (href === "/" ? pathname === "/" : pathname.startsWith(href)) ? "bg-accent font-medium text-accent-foreground" : "text-muted-foreground hover:bg-muted")}>
          <Icon className="h-4 w-4" aria-hidden />{label}
        </Link>
      ))}
    </nav>
  );
  return (
    <div className="flex min-h-screen">
      <aside className="hidden w-64 shrink-0 border-r border-border bg-card lg:block">
        <div className="flex h-16 items-center gap-2 px-5 font-semibold"><Activity className="h-5 w-5 text-primary" />Control Center</div>
        {nav}
      </aside>
      {open && <div className="fixed inset-0 z-40 overscroll-contain bg-black/40 lg:hidden" onClick={() => setOpen(false)}><aside role="dialog" aria-modal="true" aria-label="Menu"
        className="h-full w-72 max-w-[85vw] overflow-y-auto overscroll-contain bg-card" onClick={(e) => e.stopPropagation()}>
        <div className="flex h-16 items-center justify-between px-5 font-semibold">Control Center<button aria-label="Fechar" onClick={() => setOpen(false)}><X className="h-5 w-5" /></button></div>{nav}</aside></div>}
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex h-16 items-center justify-between gap-2 border-b border-border px-4">
          <button className="rounded p-2 hover:bg-muted lg:hidden" aria-label="Menu" onClick={() => setOpen(true)}><Menu className="h-5 w-5" /></button>
          <div className="ml-auto flex items-center gap-3 text-sm">
            <span className="hidden text-muted-foreground sm:inline">{user.email} · {user.role.toLowerCase()}</span>
            <Button variant="ghost" onClick={async () => { await call("/api/auth/logout", { method: "POST" }).catch(() => undefined); window.location.href = "/login"; }}>Sair</Button>
          </div>
        </header>
        <main className="min-w-0 flex-1 p-4 sm:p-6">{children}</main>
      </div>
      {reauth && <ReauthDialog onDone={(ok) => { setReauth(false); resolver.current?.(ok); }} />}
    </div>
  );
}

/**
 * Keeps the persistent session fresh while the panel is open: on load, when the tab becomes visible again, when the
 * connection comes back and every 10 minutes. The server slides the inactivity window and rotates the token when due
 * (HttpOnly cookie — nothing is stored in JavaScript). Errors are ignored: a failed ping never logs anyone out.
 */
function useSessionKeepAlive() {
  useEffect(() => {
    let last = 0;
    const ping = () => {
      if (document.visibilityState !== "visible" || Date.now() - last < 60_000) return;
      last = Date.now();
      void fetch("/api/auth/me", { credentials: "same-origin", cache: "no-store" }).then((r) => {
        if (r.status === 401) window.location.href = `/login?next=${encodeURIComponent(window.location.pathname)}`;
      }).catch(() => undefined);
    };
    ping();
    const id = setInterval(ping, 10 * 60_000);
    document.addEventListener("visibilitychange", ping);
    window.addEventListener("online", ping);
    window.addEventListener("focus", ping);
    return () => { clearInterval(id); document.removeEventListener("visibilitychange", ping); window.removeEventListener("online", ping); window.removeEventListener("focus", ping); };
  }, []);
}

/** While a drawer/dialog is open the page behind it must not scroll (on iOS it otherwise drifts under the overlay). */
function useScrollLock(locked: boolean) {
  useEffect(() => {
    if (!locked) return;
    const { overflow } = document.body.style;
    document.body.style.overflow = "hidden";
    return () => { document.body.style.overflow = overflow; };
  }, [locked]);
}

function ReauthDialog({ onDone }: { onDone: (ok: boolean) => void }) {
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center overflow-y-auto overscroll-contain bg-black/50 p-4" role="dialog" aria-modal="true" aria-labelledby="reauth-title">
      <form className="w-full max-w-sm space-y-3 rounded-lg border border-border bg-card p-5" onSubmit={async (e) => {
        e.preventDefault(); setBusy(true); setError(null);
        try { await call("/api/auth/reauth", { body: { password } }); onDone(true); } catch (err) { setError(message(err)); } finally { setBusy(false); }
      }}>
        <h2 id="reauth-title" className="font-semibold">Confirme sua identidade</h2>
        <p className="text-sm text-muted-foreground">Esta ação é sensível: confirme sua senha (vale por 15 minutos).</p>
        <Field label="Senha" id="rp"><Input id="rp" type="password" autoComplete="current-password" required autoFocus value={password} onChange={(e) => setPassword(e.target.value)} /></Field>
        {error && <Alert tone="bad">{error}</Alert>}
        <div className="flex gap-2"><Button type="submit" loading={busy}>Confirmar</Button><Button type="button" variant="outline" onClick={() => onDone(false)}>Cancelar</Button></div>
      </form>
    </div>
  );
}

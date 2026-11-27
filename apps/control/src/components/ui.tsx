"use client";
import * as React from "react";
import { Loader2 } from "lucide-react";
import { cn } from "@vendocas/design-system/cn";

export { cn };

export function Button({ variant = "primary", loading, className, children, disabled, ...p }:
  React.ButtonHTMLAttributes<HTMLButtonElement> & { variant?: "primary" | "outline" | "danger" | "ghost"; loading?: boolean }) {
  const v = { primary: "bg-primary text-primary-foreground hover:opacity-90", outline: "border border-border bg-card hover:bg-muted",
    danger: "bg-danger text-background hover:opacity-90", ghost: "hover:bg-muted" }[variant];
  return (
    <button className={cn("inline-flex h-10 items-center justify-center gap-2 rounded-lg px-4 text-sm font-medium disabled:opacity-50", v, className)}
      disabled={disabled || loading} {...p}>{loading && <Loader2 className="h-4 w-4 animate-spin" aria-hidden />}{children}</button>
  );
}

export const Input = React.forwardRef<HTMLInputElement, React.InputHTMLAttributes<HTMLInputElement>>(({ className, ...p }, ref) => (
  <input ref={ref} className={cn("h-10 w-full rounded-lg border border-input bg-card px-3 text-sm", className)} {...p} />
));
Input.displayName = "Input";

export function Field({ label, hint, error, children, id }: { label: string; hint?: string; error?: string; children: React.ReactNode; id?: string }) {
  return (
    <div className="space-y-1">
      <label htmlFor={id} className="block text-sm font-medium">{label}</label>
      {children}
      {hint && !error && <p className="text-xs text-muted-foreground">{hint}</p>}
      {error && <p className="text-xs text-danger" role="alert">{error}</p>}
    </div>
  );
}

export function Card({ className, ...p }: React.HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("rounded-lg border border-border bg-card p-4 shadow-sm sm:p-5", className)} {...p} />;
}

export function Title({ children, actions }: { children: React.ReactNode; actions?: React.ReactNode }) {
  return <div className="mb-3 flex flex-wrap items-center justify-between gap-2"><h2 className="text-base font-semibold">{children}</h2>{actions}</div>;
}

const TONES: Record<string, string> = {
  ok: "bg-success/15 text-success", warn: "bg-warning/20 text-foreground", bad: "bg-danger/15 text-danger", neutral: "bg-muted text-muted-foreground", info: "bg-accent text-accent-foreground",
};
export function Badge({ tone = "neutral", children }: { tone?: keyof typeof TONES; children: React.ReactNode }) {
  return <span className={cn("inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium", TONES[tone])}>{children}</span>;
}

export function stateTone(s?: string | null): keyof typeof TONES {
  if (!s) return "neutral";
  if (["Available", "RUNNING", "VALID", "SUCCESS", "UP", "ACTIVE", "completed", "success"].includes(s)) return "ok";
  if (["Starting", "ShuttingDown", "STARTING", "STOP_REQUESTED", "UNTESTED", "Queued", "in_progress", "queued", "WARN"].includes(s)) return "warn";
  if (["Failed", "FAILED", "INVALID", "FAILURE", "DENIED", "DOWN", "ERROR", "failure", "REVOKED"].includes(s)) return "bad";
  return "neutral";
}

export function Alert({ tone = "info", children }: { tone?: "info" | "bad" | "warn" | "ok"; children: React.ReactNode }) {
  const c = { info: "border-primary/30 bg-accent", bad: "border-danger/40 bg-danger/10", warn: "border-warning/40 bg-warning/10", ok: "border-success/40 bg-success/10" }[tone];
  return <div role={tone === "bad" ? "alert" : "status"} className={cn("rounded-lg border p-3 text-sm", c)}>{children}</div>;
}

export function Table({ head, children }: { head: string[]; children: React.ReactNode }) {
  return (
    <div className="overflow-x-auto rounded-lg border border-border">
      <table className="w-full text-left text-sm">
        <thead className="bg-muted/60 text-xs uppercase text-muted-foreground"><tr>{head.map((h) => <th key={h} className="px-3 py-2 font-medium">{h}</th>)}</tr></thead>
        <tbody className="divide-y divide-border">{children}</tbody>
      </table>
    </div>
  );
}
export const Td = ({ className, ...p }: React.TdHTMLAttributes<HTMLTableCellElement>) => <td className={cn("px-3 py-2 align-top", className)} {...p} />;

export const fmt = (iso?: string | null) => (iso ? new Date(iso).toLocaleString() : "—");

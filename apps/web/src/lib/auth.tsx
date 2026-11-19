"use client";
import * as React from "react";
import { api, refreshAccessToken, session } from "./api";

export interface Membership { organizationId: string; organizationName: string; role: string; countryCode: string }
export interface Me { id: string; email: string; fullName: string; locale: string; timeZone: string; emailVerified: boolean; mfaEnabled: boolean;
  platformRole?: string | null; memberships: Membership[] }

interface AuthState {
  status: "loading" | "anonymous" | "authenticated";
  me: Me | null;
  orgId: string | null;
  membership: Membership | null;
  setOrg: (id: string) => void;
  reload: () => Promise<void>;
  signIn: (accessToken: string) => Promise<void>;
  signOut: () => Promise<void>;
}

const Ctx = React.createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [status, setStatus] = React.useState<AuthState["status"]>("loading");
  const [me, setMe] = React.useState<Me | null>(null);
  const [orgId, setOrgId] = React.useState<string | null>(null);

  const load = React.useCallback(async () => {
    const m = await api<Me>("/auth/me", { tenant: false });
    setMe(m);
    const saved = session.savedOrg();
    const chosen = m.memberships.find((x) => x.organizationId === saved)?.organizationId ?? m.memberships[0]?.organizationId ?? null;
    session.setOrg(chosen);
    setOrgId(chosen);
    setStatus("authenticated");
  }, []);

  React.useEffect(() => {
    (async () => {
      const t = session.getToken() ?? (await refreshAccessToken());
      if (!t) { setStatus("anonymous"); return; }
      try { await load(); } catch { setStatus("anonymous"); }
    })();
  }, [load]);

  const value: AuthState = {
    status, me, orgId,
    membership: me?.memberships.find((m) => m.organizationId === orgId) ?? null,
    setOrg: (id) => { session.setOrg(id); setOrgId(id); },
    reload: load,
    signIn: async (token) => { session.setToken(token); await load(); },
    signOut: async () => {
      await fetch("/api/v1/auth/logout", { method: "POST", credentials: "include", headers: { "X-Requested-With": "XMLHttpRequest" } }).catch(() => undefined);
      session.setToken(null);
      session.setOrg(null);
      setMe(null);
      setStatus("anonymous");
    },
  };
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useAuth(): AuthState {
  const v = React.useContext(Ctx);
  if (!v) throw new Error("useAuth outside AuthProvider");
  return v;
}

const WRITE_ROLES: Record<string, string[]> = {
  billing: ["OWNER", "FINANCE"],
  manage: ["OWNER", "ADMIN"],
  operate: ["OWNER", "ADMIN", "OPERATOR"],
};

export function useCan(kind: keyof typeof WRITE_ROLES): boolean {
  const { membership } = useAuth();
  return !!membership && WRITE_ROLES[kind]!.includes(membership.role);
}

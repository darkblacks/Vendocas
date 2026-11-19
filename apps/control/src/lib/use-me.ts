"use client";
import { useEffect, useState } from "react";
import { call } from "./client";

export interface Me { id: string; email: string; displayName: string; role: "OWNER" | "ADMIN" | "VIEWER" }

export function useMe(): Me | null {
  const [me, setMe] = useState<Me | null>(null);
  useEffect(() => { call<{ user: Me }>("/api/auth/me").then((r) => setMe(r.user)).catch(() => undefined); }, []);
  return me;
}

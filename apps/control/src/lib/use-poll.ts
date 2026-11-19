"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import { call } from "./client";

/** Polls a GET endpoint; pauses while the tab is hidden; never overlaps requests. */
export function usePoll<T>(path: string | null, intervalMs = 15_000) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const busy = useRef(false);
  const load = useCallback(async () => {
    if (!path || busy.current) return;
    busy.current = true;
    try { setData(await call<T>(path)); setError(null); } catch (e) { setError(e instanceof Error ? e.message : String(e)); } finally { busy.current = false; }
  }, [path]);
  useEffect(() => {
    void load();
    if (!intervalMs) return;
    const id = setInterval(() => { if (document.visibilityState === "visible") void load(); }, intervalMs);
    return () => clearInterval(id);
  }, [load, intervalMs]);
  return { data, error, reload: load };
}

export function useNow(ms = 1000) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => { const id = setInterval(() => setNow(Date.now()), ms); return () => clearInterval(id); }, [ms]);
  return now;
}

export function duration(ms: number): string {
  if (ms <= 0) return "00:00:00";
  const s = Math.floor(ms / 1000);
  return [Math.floor(s / 3600), Math.floor((s % 3600) / 60), s % 60].map((n) => String(n).padStart(2, "0")).join(":");
}

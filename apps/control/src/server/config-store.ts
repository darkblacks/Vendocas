import { chmod, mkdir, readFile, rename, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { ConfigError, env } from "./env";

/**
 * Persistent key/value store for installation state that must exist BEFORE (or independently of) the database: the
 * encrypted PostgreSQL connection string and the installation lock. It lives outside the Codespace and outside
 * function instances:
 *
 *  - production (Netlify): Netlify Blobs, store "vendocas-control-config";
 *  - local development: files under CONTROL_STATE_DIR (default apps/control/.control-state, git-ignored, mode 600).
 *
 * Every operation is time-bounded. The Blobs SDK retries failed requests up to 5 times with 5-second pauses and its
 * fetch has no timeout — together that exceeded the 30 s gateway limit and produced HTML 504 pages. Here the SDK gets
 * a fetch with a per-request timeout whose failures are reported as non-retryable, and each operation has an overall
 * deadline, so callers always get a clear JSON error (CONFIG_STORE_UNAVAILABLE / CONFIG_STORE_TIMEOUT) quickly.
 * Values written here are already encrypted by the caller; this layer never sees plaintext secrets.
 */
export interface ConfigStore {
  get(key: string): Promise<string | null>;
  set(key: string, value: string): Promise<void>;
  delete(key: string): Promise<void>;
}

const REQUEST_TIMEOUT_MS = 2500;
const OPERATION_TIMEOUT_MS = 4000;

let override: ConfigStore | null = null;
let cached: ConfigStore | null = null;

/** Tests: in-memory (or deliberately broken) store. */
export function __setConfigStore(s: ConfigStore | null) { override = s; cached = null; }

export function memoryConfigStore(): ConfigStore & { data: Map<string, string> } {
  const data = new Map<string, string>();
  return { data, get: async (k) => data.get(k) ?? null, set: async (k, v) => { data.set(k, v); }, delete: async (k) => { data.delete(k); } };
}

export function storeMode(): "netlify-blobs" | "file" {
  const m = process.env.CONTROL_CONFIG_STORE?.trim();
  if (m === "file" || m === "netlify-blobs") return m;
  return env.isProduction() ? "netlify-blobs" : "file";
}

const unavailable = (detail: string) =>
  new ConfigError("CONFIG_STORE_UNAVAILABLE", `The persistent configuration store (Netlify Blobs) is unavailable: ${detail}`);

/** Bounded fetch for the Blobs SDK: per-request timeout, and failures returned as a non-retryable status. */
const boundedFetch: typeof fetch = async (input, init) => {
  try {
    const res = await fetch(input, { ...init, signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS) });
    if (res.status === 429 || res.status >= 500) {
      await res.body?.cancel().catch(() => undefined);
      return new Response(null, { status: 424, headers: { "x-vcc-upstream": String(res.status) } });
    }
    return res;
  } catch (e) {
    return new Response(null, { status: 424, headers: { "x-vcc-upstream": (e as Error).name } });
  }
};

function withDeadline<T>(p: Promise<T>, ms = OPERATION_TIMEOUT_MS): Promise<T> {
  let t: ReturnType<typeof setTimeout>;
  return Promise.race([
    p.finally(() => clearTimeout(t)),
    new Promise<never>((_, rej) => { t = setTimeout(() => rej(new ConfigError("CONFIG_STORE_TIMEOUT", `The persistent configuration store did not answer within ${ms / 1000}s`)), ms); }),
  ]);
}

async function blobs(): Promise<ConfigStore> {
  let sdk: typeof import("@netlify/blobs");
  try { sdk = await import("@netlify/blobs"); } catch { throw unavailable("SDK not bundled"); }
  type Store = ReturnType<typeof sdk.getStore>;
  const make = (consistency: "strong" | "eventual"): Store => {
    try { return sdk.getStore({ name: "vendocas-control-config", consistency, fetch: boundedFetch }); } catch (e) {
      throw unavailable((e as Error).name === "MissingBlobsEnvironmentError" ? "this deployment has no Netlify Blobs context" : (e as Error).name);
    }
  };
  let strong: Store | null = make("strong");
  let eventual: Store | null = null;
  const run = async <T>(f: (s: Store) => Promise<T>): Promise<T> => {
    try {
      return await withDeadline((async () => {
        if (strong) {
          try { return await f(strong); } catch (e) {
            if ((e as Error).name !== "BlobsConsistencyError") throw e;
            // Environment without the uncached edge endpoint: fall back to eventual consistency (documented).
            console.warn("[config-store] strong consistency unavailable; using eventual consistency");
            strong = null;
          }
        }
        eventual ??= make("eventual");
        return f(eventual);
      })());
    } catch (e) {
      if (e instanceof ConfigError) throw e;
      console.error("[config-store] netlify blobs failure", (e as Error).name); // no message: it may contain URLs
      throw unavailable((e as Error).name);
    }
  };
  return {
    get: (k) => run(async (s) => (await s.get(k, { type: "text" })) ?? null),
    set: (k, v) => run(async (s) => { await s.set(k, v); }),
    delete: (k) => run((s) => s.delete(k)),
  };
}

function files(): ConfigStore {
  const dir = process.env.CONTROL_STATE_DIR?.trim() || path.join(process.cwd(), ".control-state");
  const file = (k: string) => path.join(dir, `${k.replace(/[^a-z0-9_-]/gi, "_")}.json`);
  return {
    async get(k) { try { return await readFile(file(k), "utf8"); } catch { return null; } },
    async set(k, v) {
      await mkdir(dir, { recursive: true, mode: 0o700 });
      const tmp = `${file(k)}.${process.pid}.tmp`;
      await writeFile(tmp, v, { mode: 0o600 });
      await rename(tmp, file(k));
      await chmod(file(k), 0o600);
    },
    async delete(k) { await rm(file(k), { force: true }); },
  };
}

export async function configStore(): Promise<ConfigStore> {
  if (override) return { get: (k) => withDeadline(override!.get(k)), set: (k, v) => withDeadline(override!.set(k, v)), delete: (k) => withDeadline(override!.delete(k)) };
  if (!cached) cached = storeMode() === "file" ? files() : await blobs();
  return cached;
}

import { chmod, mkdir, readFile, rename, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { ConfigError, env } from "./env";

/**
 * Small persistent key/value store for configuration that must exist BEFORE the database does (the encrypted
 * database connection string). It lives outside the Codespace and outside function instances:
 *
 *  - production (Netlify): Netlify Blobs, store "vendocas-control-config", strong consistency — persisted by Netlify
 *    across deploys and cold starts (not the function's temporary filesystem);
 *  - local development: files under CONTROL_STATE_DIR (default apps/control/.control-state, git-ignored, mode 600).
 *
 * Values written here are already encrypted by the caller; this layer never sees plaintext secrets.
 */
export interface ConfigStore {
  get(key: string): Promise<string | null>;
  set(key: string, value: string): Promise<void>;
  delete(key: string): Promise<void>;
}

let override: ConfigStore | null = null;
let cached: ConfigStore | null = null;

/** Tests: in-memory store. */
export function __setConfigStore(s: ConfigStore | null) { override = s; cached = null; }

export function memoryConfigStore(): ConfigStore & { data: Map<string, string> } {
  const data = new Map<string, string>();
  return { data, get: async (k) => data.get(k) ?? null, set: async (k, v) => { data.set(k, v); }, delete: async (k) => { data.delete(k); } };
}

function mode(): "netlify-blobs" | "file" {
  const m = process.env.CONTROL_CONFIG_STORE?.trim();
  if (m === "file" || m === "netlify-blobs") return m;
  return env.isProduction() ? "netlify-blobs" : "file";
}

async function blobs(): Promise<ConfigStore> {
  const { getStore } = await import("@netlify/blobs");
  const s = getStore({ name: "vendocas-control-config", consistency: "strong" });
  const wrap = async <T>(f: () => Promise<T>): Promise<T> => {
    try { return await f(); } catch (e) {
      // The SDK error can name the site; keep only the class.
      console.error("[config-store] netlify blobs failure", (e as Error).name);
      throw new ConfigError("CONFIG_STORE_UNAVAILABLE", "Persistent configuration store (Netlify Blobs) is unavailable");
    }
  };
  return {
    get: (k) => wrap(async () => (await s.get(k, { type: "text" })) ?? null),
    set: (k, v) => wrap(async () => { await s.set(k, v); }),
    delete: (k) => wrap(() => s.delete(k)),
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
  if (override) return override;
  if (!cached) cached = mode() === "file" ? files() : await blobs();
  return cached;
}

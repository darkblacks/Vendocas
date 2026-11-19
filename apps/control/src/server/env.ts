/** Server-only configuration. Every secret comes from the hosting environment (Netlify env vars), never from Git. */
export class ConfigError extends Error {
  constructor(public code: string, message: string) {
    super(message);
  }
}

function req(name: string): string {
  const v = process.env[name];
  if (!v || !v.trim()) throw new ConfigError("CONFIG_MISSING", `${name} is not configured`);
  return v.trim();
}

export const env = {
  databaseUrl: () => req("CONTROL_DATABASE_URL"),
  /** "v1:<base64 32 bytes>,v2:<base64 32 bytes>" — all versions able to decrypt. */
  masterKeys: () => req("CONTROL_MASTER_KEYS"),
  /** Version used for new encryptions, e.g. "v2". */
  activeKeyVersion: () => req("CONTROL_ACTIVE_KEY_VERSION"),
  /** One-time bootstrap secret (only usable while no owner exists). */
  bootstrapToken: () => process.env.CONTROL_BOOTSTRAP_TOKEN?.trim() || null,
  bootstrapEmail: () => process.env.CONTROL_BOOTSTRAP_EMAIL?.trim().toLowerCase() || null,
  /** Repository whose Codespace may be controlled (server-side allowlist; never taken from the browser). */
  githubRepository: () => process.env.CONTROL_GITHUB_REPOSITORY?.trim() || null,
  /** Public origin of this app, used for Origin checks on state-changing requests. */
  publicOrigin: () => process.env.CONTROL_PUBLIC_ORIGIN?.trim() || null,
  /** Secret that authorizes the scheduled function when invoked over HTTP (Netlify scheduled functions run internally). */
  isProduction: () => process.env.NODE_ENV === "production",
  githubApiUrl: () => process.env.CONTROL_GITHUB_API_URL?.trim() || "https://api.github.com",
};

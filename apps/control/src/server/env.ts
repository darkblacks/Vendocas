/**
 * Server-only configuration. The ONLY variables an owner has to set on Netlify are:
 *
 *   CONTROL_SETUP_EMAIL  e-mail of the installation owner
 *   CONTROL_SETUP_KEY    installation key (>= 32 random characters) — root of trust: every encryption key and the
 *                        setup-session signing key are derived from it with HKDF-SHA256 (see crypto.ts)
 *
 * Everything else (database, GitHub repository, links, provider credentials) is configured in the UI and stored
 * encrypted. Netlify's own automatic variables (URL, DEPLOY_PRIME_URL, NODE_ENV) are read when present.
 */
export class ConfigError extends Error {
  constructor(public code: string, message: string) {
    super(message);
  }
}

export const MIN_SETUP_KEY_LENGTH = 32;

export const env = {
  /** Current installation key, or null when absent/too short (callers decide whether that is fatal). */
  setupKey: (): string | null => {
    const v = process.env.CONTROL_SETUP_KEY?.trim();
    return v && v.length >= MIN_SETUP_KEY_LENGTH ? v : null;
  },
  /** Previous installation key — only set temporarily while rotating the key (decrypt-only). */
  previousSetupKey: (): string | null => {
    const v = process.env.CONTROL_SETUP_KEY_PREVIOUS?.trim();
    return v && v.length >= MIN_SETUP_KEY_LENGTH ? v : null;
  },
  setupEmail: (): string | null => process.env.CONTROL_SETUP_EMAIL?.trim().toLowerCase() || null,
  /** Origins accepted for state-changing requests: optional explicit value + Netlify's automatic site URLs. */
  allowedOrigins: (): string[] => [process.env.CONTROL_PUBLIC_ORIGIN, process.env.URL, process.env.DEPLOY_PRIME_URL]
    .map((v) => v?.trim()).filter((v): v is string => !!v).map((v) => new URL(v).origin),
  isProduction: () => process.env.NODE_ENV === "production",
  githubApiUrl: () => process.env.CONTROL_GITHUB_API_URL?.trim() || "https://api.github.com",
};

export function requireSetupKey(): string {
  const k = env.setupKey();
  if (!k) throw new ConfigError("SETUP_NOT_CONFIGURED", `CONTROL_SETUP_KEY is not configured on the server (at least ${MIN_SETUP_KEY_LENGTH} characters)`);
  return k;
}

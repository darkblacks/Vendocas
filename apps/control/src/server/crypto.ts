import { createCipheriv, createDecipheriv, createHash, randomBytes, timingSafeEqual, randomUUID } from "node:crypto";
import { ConfigError, env } from "./env";

/**
 * Envelope for vault records: AES-256-GCM, a fresh 96-bit nonce per encryption, the record identity bound as
 * additional authenticated data (so a ciphertext cannot be swapped onto another provider/record), and a key
 * version so keys can be rotated. Keys live only in the hosting environment (CONTROL_MASTER_KEYS).
 */
export interface Sealed { ciphertext: string; nonce: string; keyVersion: string }

export function parseKeys(raw = env.masterKeys()): Map<string, Buffer> {
  const keys = new Map<string, Buffer>();
  for (const part of raw.split(",").map((s) => s.trim()).filter(Boolean)) {
    const idx = part.indexOf(":");
    if (idx < 1) throw new ConfigError("MASTER_KEY_INVALID", "CONTROL_MASTER_KEYS must look like v1:<base64>");
    const version = part.slice(0, idx);
    const key = Buffer.from(part.slice(idx + 1), "base64");
    if (key.length !== 32) throw new ConfigError("MASTER_KEY_INVALID", `Master key ${version} must be 32 bytes`);
    keys.set(version, key);
  }
  if (keys.size === 0) throw new ConfigError("MASTER_KEY_MISSING", "No master key configured");
  return keys;
}

export function activeKey(): { version: string; key: Buffer } {
  const keys = parseKeys();
  const version = env.activeKeyVersion();
  const key = keys.get(version);
  if (!key) throw new ConfigError("MASTER_KEY_MISSING", `Active key version ${version} not present in CONTROL_MASTER_KEYS`);
  return { version, key };
}

export function seal(plaintext: string, aad: string, k = activeKey()): Sealed {
  const nonce = randomBytes(12);
  const c = createCipheriv("aes-256-gcm", k.key, nonce);
  c.setAAD(Buffer.from(aad, "utf8"));
  const ct = Buffer.concat([c.update(plaintext, "utf8"), c.final(), c.getAuthTag()]);
  return { ciphertext: ct.toString("base64"), nonce: nonce.toString("base64"), keyVersion: k.version };
}

export function open(s: Sealed, aad: string): string {
  const key = parseKeys().get(s.keyVersion);
  if (!key) throw new ConfigError("MASTER_KEY_MISSING", `Key version ${s.keyVersion} is not available to decrypt`);
  const all = Buffer.from(s.ciphertext, "base64");
  const d = createDecipheriv("aes-256-gcm", key, Buffer.from(s.nonce, "base64"));
  d.setAAD(Buffer.from(aad, "utf8"));
  d.setAuthTag(all.subarray(all.length - 16));
  return Buffer.concat([d.update(all.subarray(0, all.length - 16)), d.final()]).toString("utf8");
}

export const sha256 = (s: string) => createHash("sha256").update(s).digest("hex");
export const randomToken = (bytes = 32) => randomBytes(bytes).toString("base64url");
export const uuid = () => randomUUID();

export function safeEqual(a: string, b: string): boolean {
  const ab = Buffer.from(a), bb = Buffer.from(b);
  return ab.length === bb.length && timingSafeEqual(ab, bb);
}

/** "••••1234" style hint. Short values are fully masked. */
export function mask(value: string): string {
  if (!value) return "";
  return value.length <= 8 ? "••••" : `••••${value.slice(-4)}`;
}

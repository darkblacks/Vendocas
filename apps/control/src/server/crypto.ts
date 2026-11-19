import { createCipheriv, createDecipheriv, createHash, createHmac, hkdfSync, randomBytes, timingSafeEqual, randomUUID } from "node:crypto";
import { ConfigError, env, requireSetupKey } from "./env";

/**
 * Key hierarchy — everything derives from CONTROL_SETUP_KEY with HKDF-SHA256 and a distinct context ("info") per use:
 *
 *   install-auth  HMAC key that signs the short-lived setup session (proves the installation key was presented)
 *   config        AES-256-GCM key for installation config kept outside the database (the PostgreSQL connection string)
 *   data          AES-256-GCM key for data inside the database (vault credentials, MFA secrets, challenges)
 *
 * Sealed records carry a key id (a fingerprint of the setup key, never the key) so CONTROL_SETUP_KEY can be rotated:
 * the previous key stays usable for decryption via CONTROL_SETUP_KEY_PREVIOUS until everything is re-encrypted.
 * Every encryption uses a fresh 96-bit nonce and binds the record identity as additional authenticated data.
 */
export interface Sealed { ciphertext: string; nonce: string; keyVersion: string }
export type Purpose = "config" | "data";
interface Key { version: string; key: Buffer }

const SALT = Buffer.from("vendocas-control-center/hkdf-salt/v1", "utf8");
const INFO = { "install-auth": "vendocas-control/v1/install-auth", config: "vendocas-control/v1/config-encryption", data: "vendocas-control/v1/data-encryption", id: "vendocas-control/v1/key-id" } as const;

const cache = new Map<string, Buffer>();
function derive(ikm: string, info: string): Buffer {
  const id = `${sha256(ikm)}:${info}`;
  let k = cache.get(id);
  if (!k) { k = Buffer.from(hkdfSync("sha256", Buffer.from(ikm, "utf8"), SALT, Buffer.from(info, "utf8"), 32)); cache.set(id, k); }
  return k;
}
const keyId = (ikm: string) => `sk-${derive(ikm, INFO.id).toString("hex").slice(0, 12)}`;

/** Signing key for the setup session (separate HKDF context from every encryption key). */
export const installAuthKey = () => derive(requireSetupKey(), INFO["install-auth"]);

export function activeKey(purpose: Purpose = "data"): Key {
  const k = requireSetupKey();
  return { version: keyId(k), key: derive(k, INFO[purpose]) };
}

/** All keys able to decrypt for a purpose: current setup key, previous one (during rotation), legacy master keys. */
export function keyring(purpose: Purpose = "data"): Map<string, Buffer> {
  const ring = new Map<string, Buffer>();
  for (const ikm of [env.setupKey(), env.previousSetupKey()]) if (ikm) ring.set(keyId(ikm), derive(ikm, INFO[purpose]));
  // Installations created before the setup-key model used CONTROL_MASTER_KEYS="v1:<b64>"; still readable, so a
  // rotation can move that data to the setup key. Not required for new installations.
  if (purpose === "data") {
    for (const part of (process.env.CONTROL_MASTER_KEYS ?? "").split(",").map((s) => s.trim()).filter(Boolean)) {
      const i = part.indexOf(":");
      const key = i > 0 ? Buffer.from(part.slice(i + 1), "base64") : Buffer.alloc(0);
      if (key.length === 32) ring.set(part.slice(0, i), key);
    }
  }
  return ring;
}

export function seal(plaintext: string, aad: string, k: Key = activeKey("data")): Sealed {
  const nonce = randomBytes(12);
  const c = createCipheriv("aes-256-gcm", k.key, nonce);
  c.setAAD(Buffer.from(aad, "utf8"));
  const ct = Buffer.concat([c.update(plaintext, "utf8"), c.final(), c.getAuthTag()]);
  return { ciphertext: ct.toString("base64"), nonce: nonce.toString("base64"), keyVersion: k.version };
}

export function open(s: Sealed, aad: string, purpose: Purpose = "data"): string {
  const key = keyring(purpose).get(s.keyVersion);
  if (!key) {
    if (!env.setupKey()) requireSetupKey();
    throw new ConfigError("SETUP_KEY_MISMATCH", "Encrypted data was sealed with a different CONTROL_SETUP_KEY (set CONTROL_SETUP_KEY_PREVIOUS to the old key to recover)");
  }
  const all = Buffer.from(s.ciphertext, "base64");
  const d = createDecipheriv("aes-256-gcm", key, Buffer.from(s.nonce, "base64"));
  d.setAAD(Buffer.from(aad, "utf8"));
  d.setAuthTag(all.subarray(all.length - 16));
  return Buffer.concat([d.update(all.subarray(0, all.length - 16)), d.final()]).toString("utf8");
}

export const hmac = (key: Buffer, data: string) => createHmac("sha256", key).update(data).digest("base64url");
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

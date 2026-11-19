import { randomBytes, scrypt as scryptCb, timingSafeEqual } from "node:crypto";

/** scrypt (N=2^15, r=8, p=1) — memory-hard, built into Node (no native addon needed on serverless). */
const N = 32768, R = 8, P = 1, LEN = 64;

function scrypt(pw: string, salt: Buffer): Promise<Buffer> {
  return new Promise((res, rej) => scryptCb(pw, salt, LEN, { N, r: R, p: P, maxmem: 64 * 1024 * 1024 }, (e, k) => (e ? rej(e) : res(k))));
}

export async function hashPassword(pw: string): Promise<string> {
  const salt = randomBytes(16);
  return `scrypt$${N}$${R}$${P}$${salt.toString("base64")}$${(await scrypt(pw, salt)).toString("base64")}`;
}

export async function verifyPassword(pw: string, stored: string): Promise<boolean> {
  const [alg, , , , salt, hash] = stored.split("$");
  if (alg !== "scrypt" || !salt || !hash) return false;
  const k = await scrypt(pw, Buffer.from(salt, "base64"));
  const h = Buffer.from(hash, "base64");
  return h.length === k.length && timingSafeEqual(h, k);
}

export function passwordProblem(pw: string, email: string): string | null {
  if (pw.length < 14) return "Password must have at least 14 characters";
  if (pw.toLowerCase().includes(email.split("@")[0]!.toLowerCase())) return "Password must not contain the email name";
  if (new Set(pw).size < 6) return "Password is too simple";
  return null;
}

/** Equalizes timing for unknown users. */
export const DUMMY_HASH = "scrypt$32768$8$1$AAAAAAAAAAAAAAAAAAAAAA==$" + Buffer.alloc(64).toString("base64");

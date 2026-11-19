import type pg from "pg";
import { audit } from "./audit";
import type { AuthContext } from "./auth";
import { activeKey, mask, open, seal, uuid } from "./crypto";
import { db, tx } from "./db";
import { HttpError, bad, redact } from "./http";
import { PROVIDERS, provider, type Environment, type ProviderDef } from "./providers";

/**
 * Credentials vault. Plaintext exists only inside this module's function scope: it is decrypted to run a
 * provider test or to serve a server-side caller (e.g. the Codespaces controller), never returned by any route.
 */
export interface CredentialView {
  id: string; provider: string; environment: Environment; status: string; masked: Record<string, string>;
  keyVersion: string; lastTestedAt: string | null; lastErrorCode: string | null; lastErrorMessage: string | null;
  updatedAt: string; updatedBy: string | null; version: number;
}

const aad = (id: string, providerId: string, environment: string) => `vault:${id}:${providerId}:${environment}`;

export function catalog() {
  return PROVIDERS.map((p) => ({
    id: p.id, name: p.name, description: p.description, docsUrl: p.docsUrl, environments: p.environments,
    testable: !!p.test, revocableAtProvider: !!p.revoke, revokeHelp: p.revokeHelp,
    fields: p.fields.map(({ key, label, secret, required, placeholder, help, defaultValue, pattern }) =>
      ({ key, label, secret, required, placeholder, help, defaultValue, pattern: pattern?.source })),
  }));
}

function definition(id: string): ProviderDef {
  const p = provider(id);
  if (!p) throw new HttpError(404, "UNKNOWN_PROVIDER", "Unknown provider");
  return p;
}

export function validate(p: ProviderDef, environment: Environment, values: Record<string, unknown>): Record<string, string> {
  if (!p.environments.includes(environment)) throw bad(`${p.name} does not support environment ${environment}`);
  const clean: Record<string, string> = {};
  const errors: Record<string, string> = {};
  for (const f of p.fields) {
    const raw = values[f.key];
    const v = typeof raw === "string" ? raw.trim() : raw == null ? "" : String(raw);
    const value = v || f.defaultValue || "";
    if (!value) { if (f.required) errors[f.key] = "required"; continue; }
    if (value.length > 4096) { errors[f.key] = "too long"; continue; }
    if (f.pattern && !f.pattern.test(value)) { errors[f.key] = "invalid format"; continue; }
    clean[f.key] = value;
  }
  for (const k of Object.keys(values)) if (!p.fields.some((f) => f.key === k)) errors[k] = "unknown field";
  if (Object.keys(errors).length) throw bad("Invalid credential fields", { fields: errors });
  const implied = p.impliedEnvironment?.(clean);
  if (implied && implied !== environment) throw bad(`These values are ${implied} credentials but ${environment} was selected`);
  return clean;
}

function maskedHint(p: ProviderDef, values: Record<string, string>): Record<string, string> {
  const out: Record<string, string> = {};
  for (const f of p.fields) {
    const v = values[f.key];
    if (v) out[f.key] = f.secret ? mask(v) : v;
  }
  return out;
}

function view(r: Record<string, unknown>): CredentialView {
  return {
    id: r.id as string, provider: r.provider as string, environment: r.environment as Environment, status: r.status as string,
    masked: r.masked_hint as Record<string, string>, keyVersion: r.encryption_key_version as string,
    lastTestedAt: (r.last_tested_at as Date | null)?.toISOString() ?? null, lastErrorCode: (r.last_error_code as string) ?? null,
    lastErrorMessage: (r.last_error_message as string) ?? null, updatedAt: (r.updated_at as Date).toISOString(),
    updatedBy: (r.updated_by_email as string) ?? null, version: r.version as number,
  };
}

const SELECT = `SELECT c.*, u.email AS updated_by_email FROM integration_credentials c LEFT JOIN cc_users u ON u.id = c.updated_by`;

export async function list(): Promise<CredentialView[]> {
  const p = await db();
  return (await p.query(`${SELECT} ORDER BY c.provider, c.environment, c.updated_at DESC`)).rows.map(view);
}

async function history(c: pg.PoolClient | pg.Pool, ctx: { id: string | null; label: string }, id: string, providerId: string, environment: string, action: string, detail: Record<string, unknown> = {}) {
  await c.query(`INSERT INTO integration_credential_history (credential_id, provider, environment, action, actor_id, actor_label, detail)
                 VALUES ($1,$2,$3,$4,$5,$6,$7::jsonb)`, [id, providerId, environment, action, ctx.id, ctx.label, redact(JSON.stringify(detail))]);
}

/** Creates or replaces the live credential for provider+environment. The previous ciphertext is overwritten. */
export async function save(ctx: AuthContext, providerId: string, environment: Environment, values: Record<string, unknown>): Promise<CredentialView> {
  const p = definition(providerId);
  const clean = validate(p, environment, values);
  const key = activeKey();
  return tx(async (c) => {
    const existing = (await c.query(`SELECT id FROM integration_credentials WHERE provider = $1 AND environment = $2 AND status NOT IN ('DISABLED','REVOKED') FOR UPDATE`,
      [providerId, environment])).rows[0];
    const id = existing?.id ?? uuid();
    const sealed = seal(JSON.stringify(clean), aad(id, providerId, environment), key);
    if (existing) {
      await c.query(`UPDATE integration_credentials SET encrypted_payload = $2, nonce = $3, encryption_key_version = $4, masked_hint = $5::jsonb,
                       status = 'UNTESTED', last_tested_at = NULL, last_error_code = NULL, last_error_message = NULL, updated_by = $6,
                       updated_at = now(), version = version + 1 WHERE id = $1`,
        [id, sealed.ciphertext, sealed.nonce, sealed.keyVersion, JSON.stringify(maskedHint(p, clean)), ctx.user.id]);
    } else {
      await c.query(`INSERT INTO integration_credentials (id, provider, environment, encrypted_payload, nonce, encryption_key_version, masked_hint, status, created_by, updated_by)
                     VALUES ($1,$2,$3,$4,$5,$6,$7::jsonb,'UNTESTED',$8,$8)`,
        [id, providerId, environment, sealed.ciphertext, sealed.nonce, sealed.keyVersion, JSON.stringify(maskedHint(p, clean)), ctx.user.id]);
    }
    await history(c, ctx.actor, id, providerId, environment, existing ? "REPLACED" : "CREATED", { fields: Object.keys(clean) });
    await audit(ctx.actor, existing ? "vault.replace" : "vault.create", "SUCCESS", `${providerId}/${environment}`, {}, c);
    return view((await c.query(`${SELECT} WHERE c.id = $1`, [id])).rows[0]);
  });
}

async function liveRow(providerId: string, environment: Environment) {
  const p = await db();
  const r = (await p.query(`SELECT * FROM integration_credentials WHERE provider = $1 AND environment = $2 AND status NOT IN ('DISABLED','REVOKED')`,
    [providerId, environment])).rows[0];
  if (!r) throw new HttpError(404, "CREDENTIAL_NOT_FOUND", `No ${providerId} ${environment} credential stored`);
  return r;
}

/** Server-side only. Never pass the result to a Response. */
export async function reveal(providerId: string, environment: Environment): Promise<Record<string, string>> {
  const r = await liveRow(providerId, environment);
  return JSON.parse(open({ ciphertext: r.encrypted_payload, nonce: r.nonce, keyVersion: r.encryption_key_version }, aad(r.id, providerId, environment)));
}

export async function test(ctx: AuthContext | null, providerId: string, environment: Environment) {
  const p = definition(providerId);
  if (!p.test) throw bad(`${p.name} has no automatic connection test`);
  const r = await liveRow(providerId, environment);
  const values = await reveal(providerId, environment);
  const result = await p.test(values, environment);
  const pool = await db();
  await pool.query(`UPDATE integration_credentials SET status = $2, last_tested_at = now(), last_error_code = $3, last_error_message = $4 WHERE id = $1`,
    [r.id, result.ok ? "VALID" : "INVALID", result.ok ? null : result.code, result.ok ? null : redact(result.message)]);
  const actor = ctx?.actor ?? { id: null, label: "system" };
  await history(pool, actor, r.id, providerId, environment, result.ok ? "TEST_OK" : "TEST_FAILED", { code: result.code });
  await audit(actor, "vault.test", result.ok ? "SUCCESS" : "FAILURE", `${providerId}/${environment}`, { code: result.code });
  return { ok: result.ok, code: result.code, message: redact(result.message), account: result.account };
}

export async function setDisabled(ctx: AuthContext, providerId: string, environment: Environment) {
  const r = await liveRow(providerId, environment);
  const pool = await db();
  await pool.query("UPDATE integration_credentials SET status = 'DISABLED', updated_by = $2, updated_at = now() WHERE id = $1", [r.id, ctx.user.id]);
  await history(pool, ctx.actor, r.id, providerId, environment, "DISABLED");
  await audit(ctx.actor, "vault.disable", "SUCCESS", `${providerId}/${environment}`);
}

/**
 * Revocation: asks the provider to revoke when it offers an API, then destroys the local ciphertext either way.
 * The record keeps only metadata so the history stays auditable.
 */
export async function revoke(ctx: AuthContext, providerId: string, environment: Environment) {
  const p = definition(providerId);
  const r = await liveRow(providerId, environment);
  let providerResult: { ok: boolean; code: string; message: string } | null = null;
  if (p.revoke) {
    const values = await reveal(providerId, environment);
    providerResult = await p.revoke(values);
  }
  const tomb = seal("{}", aad(r.id, providerId, environment));
  const pool = await db();
  await pool.query(`UPDATE integration_credentials SET status = 'REVOKED', encrypted_payload = $2, nonce = $3, encryption_key_version = $4,
                      masked_hint = '{}'::jsonb, updated_by = $5, updated_at = now() WHERE id = $1`,
    [r.id, tomb.ciphertext, tomb.nonce, tomb.keyVersion, ctx.user.id]);
  await history(pool, ctx.actor, r.id, providerId, environment, "REVOKED", { providerRevocation: providerResult?.code ?? "UNSUPPORTED" });
  await audit(ctx.actor, "vault.revoke", "SUCCESS", `${providerId}/${environment}`, { providerRevocation: providerResult?.code ?? "UNSUPPORTED" });
  return { providerRevocation: providerResult ? { ok: providerResult.ok, code: providerResult.code, message: redact(providerResult.message) } : null, help: p.revokeHelp };
}

export async function historyFor(providerId: string) {
  const p = await db();
  return (await p.query(`SELECT action, environment, actor_label AS "actor", detail, created_at AS "createdAt" FROM integration_credential_history
                         WHERE provider = $1 ORDER BY created_at DESC LIMIT 100`, [providerId])).rows;
}

/**
 * Re-encrypts every vault record and every stored TOTP secret with the active master key version. Run after adding
 * a new key version to CONTROL_MASTER_KEYS and switching CONTROL_ACTIVE_KEY_VERSION; afterwards the old version can be removed.
 */
export async function rotateEncryptionKeys(ctx: AuthContext) {
  const key = activeKey();
  return tx(async (c) => {
    let credentials = 0, users = 0;
    const rows = (await c.query("SELECT * FROM integration_credentials WHERE encryption_key_version <> $1 FOR UPDATE", [key.version])).rows;
    for (const r of rows) {
      const a = aad(r.id, r.provider, r.environment);
      const plain = open({ ciphertext: r.encrypted_payload, nonce: r.nonce, keyVersion: r.encryption_key_version }, a);
      const s = seal(plain, a, key);
      await c.query("UPDATE integration_credentials SET encrypted_payload = $2, nonce = $3, encryption_key_version = $4 WHERE id = $1", [r.id, s.ciphertext, s.nonce, s.keyVersion]);
      await history(c, ctx.actor, r.id, r.provider, r.environment, "KEY_ROTATED", { from: r.encryption_key_version, to: key.version });
      credentials++;
    }
    for (const u of (await c.query("SELECT id, mfa_secret_enc FROM cc_users WHERE mfa_secret_enc IS NOT NULL FOR UPDATE")).rows) {
      const sealed = JSON.parse(u.mfa_secret_enc);
      if (sealed.keyVersion === key.version) continue;
      const a = `cc_user_mfa:${u.id}`;
      await c.query("UPDATE cc_users SET mfa_secret_enc = $2 WHERE id = $1", [u.id, JSON.stringify(seal(open(sealed, a), a, key))]);
      users++;
    }
    await c.query("DELETE FROM cc_auth_challenges WHERE used_at IS NOT NULL OR expires_at < now()");
    await audit(ctx.actor, "vault.rotate_keys", "SUCCESS", key.version, { credentials, users }, c);
    return { activeVersion: key.version, credentials, users };
  });
}

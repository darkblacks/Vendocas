import { outbound } from "../outbound";
import { signGet } from "./sigv4";

export type Environment = "TEST" | "LIVE";

export interface FieldDef {
  key: string;
  label: string;
  secret: boolean;
  required: boolean;
  placeholder?: string;
  pattern?: RegExp;
  help?: string;
  defaultValue?: string;
}

export interface TestResult { ok: boolean; code: string; message: string; account?: string }

export interface ProviderDef {
  id: string;
  name: string;
  description: string;
  docsUrl: string;
  environments: Environment[];
  fields: FieldDef[];
  /** Returns the environment implied by the values (e.g. Stripe key prefix) or null if it cannot be inferred. */
  impliedEnvironment?: (v: Record<string, string>) => Environment | null;
  test?: (v: Record<string, string>, env: Environment) => Promise<TestResult>;
  /** Provider-side revocation, when the provider offers an API for it. */
  revoke?: (v: Record<string, string>) => Promise<TestResult>;
  revokeHelp: string;
}

const ok = (message: string, account?: string): TestResult => ({ ok: true, code: "OK", message, account });
const fail = (code: string, message: string): TestResult => ({ ok: false, code, message });

async function call(url: string, init: RequestInit): Promise<Response | TestResult> {
  try {
    return await outbound(url, init);
  } catch (e) {
    return fail("UNREACHABLE", `Provider unreachable: ${(e as Error).name}`);
  }
}
const isResult = (r: Response | TestResult): r is TestResult => !(r instanceof Response);

function httpFailure(r: Response, what: string): TestResult {
  if (r.status === 401) return fail("AUTH_FAILED", `${what}: credentials rejected (401)`);
  if (r.status === 403) return fail("PERMISSION_DENIED", `${what}: authenticated but missing permission (403)`);
  if (r.status === 404) return fail("NOT_FOUND", `${what}: resource not found (404)`);
  if (r.status === 429) return fail("RATE_LIMITED", `${what}: provider rate limit (429)`);
  return fail(r.status >= 500 ? "PROVIDER_ERROR" : "UNEXPECTED_RESPONSE", `${what}: HTTP ${r.status}`);
}

const GH_API = () => process.env.CONTROL_GITHUB_API_URL?.trim() || "https://api.github.com";
const ghHeaders = (token: string) => ({ Authorization: `Bearer ${token}`, Accept: "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "vendocas-control" });

export const PROVIDERS: ProviderDef[] = [
  {
    id: "GITHUB", name: "GitHub Codespaces", description: "Token used server-side to list, start and stop the Vendocas Codespace and read workflow runs.",
    docsUrl: "https://docs.github.com/rest/codespaces", environments: ["LIVE"],
    fields: [{ key: "token", label: "Fine-grained personal access token", secret: true, required: true, pattern: /^(github_pat_|ghp_)[A-Za-z0-9_]{20,}$/,
      help: "Permissions: Codespaces (read/write), Codespaces lifecycle admin (read/write), Actions (read), Metadata (read). Only the Vendocas repository." }],
    async test(v) {
      const me = await call(`${GH_API()}/user`, { headers: ghHeaders(v.token!) });
      if (isResult(me)) return me;
      if (!me.ok) return httpFailure(me, "GitHub /user");
      const login = ((await me.json()) as { login?: string }).login;
      const cs = await call(`${GH_API()}/user/codespaces?per_page=1`, { headers: ghHeaders(v.token!) });
      if (isResult(cs)) return cs;
      if (!cs.ok) return httpFailure(cs, "GitHub Codespaces access");
      return ok("Token valid with Codespaces access", login);
    },
    async revoke(v) {
      // GitHub's credential revocation endpoint accepts leaked/unwanted tokens without authentication.
      const r = await call(`${GH_API()}/credentials/revoke`, { method: "POST", headers: { Accept: "application/vnd.github+json", "Content-Type": "application/json", "User-Agent": "vendocas-control" },
        body: JSON.stringify({ credentials: [v.token] }) });
      if (isResult(r)) return r;
      return r.status === 202 || r.ok ? ok("Revocation accepted by GitHub") : httpFailure(r, "GitHub revocation");
    },
    revokeHelp: "Also delete the token at github.com → Settings → Developer settings → Personal access tokens.",
  },
  {
    id: "VENDOCAS_API", name: "Vendocas API (homologation)", description: "Base URL of the API running in the Codespace and the status token it was started with.",
    docsUrl: "/docs/codespaces-setup.md", environments: ["TEST"],
    fields: [
      { key: "baseUrl", label: "API base URL", secret: false, required: true, pattern: /^https:\/\/[^\s/]+(:\d+)?$/, placeholder: "https://<codespace>-8080.app.github.dev" },
      { key: "statusToken", label: "CONTROL_STATUS_TOKEN", secret: true, required: true, help: "Run infrastructure/codespaces/show-status-token.sh in the Codespace terminal (or use your CONTROL_STATUS_TOKEN Codespaces secret)." },
    ],
    async test(v) {
      const r = await call(`${v.baseUrl}/internal/status`, { headers: { "X-Control-Token": v.statusToken! } });
      if (isResult(r)) return r;
      return r.ok ? ok("Status endpoint reachable") : httpFailure(r, "Vendocas /internal/status");
    },
    revokeHelp: "Rotate CONTROL_STATUS_TOKEN in the Codespace (edit /workspaces/.vendocas/secrets.env or the Codespaces secret) and restart the API.",
  },
  {
    id: "STRIPE", name: "Stripe", description: "Billing for the Vendocas subscription plans.", docsUrl: "https://docs.stripe.com/keys",
    environments: ["TEST", "LIVE"],
    fields: [
      { key: "secretKey", label: "Secret or restricted key", secret: true, required: true, pattern: /^(sk|rk)_(test|live)_[A-Za-z0-9]{10,}$/ },
      { key: "webhookSecret", label: "Webhook signing secret", secret: true, required: true, pattern: /^whsec_[A-Za-z0-9]{10,}$/ },
      { key: "publishableKey", label: "Publishable key", secret: false, required: false, pattern: /^pk_(test|live)_[A-Za-z0-9]{10,}$/ },
    ],
    impliedEnvironment: (v) => (v.secretKey?.includes("_live_") ? "LIVE" : v.secretKey?.includes("_test_") ? "TEST" : null),
    async test(v) {
      const r = await call("https://api.stripe.com/v1/balance", { headers: { Authorization: `Bearer ${v.secretKey}` } });
      if (isResult(r)) return r;
      return r.ok ? ok("Key accepted by Stripe (balance readable)") : httpFailure(r, "Stripe /v1/balance");
    },
    revokeHelp: "Roll or delete the key in the Stripe Dashboard → Developers → API keys.",
  },
  {
    id: "META_WHATSAPP", name: "Meta WhatsApp Business Platform", description: "App and system-user credentials for the Cloud API and Embedded Signup.",
    docsUrl: "https://developers.facebook.com/docs/whatsapp/cloud-api", environments: ["TEST", "LIVE"],
    fields: [
      { key: "appId", label: "App ID", secret: false, required: true, pattern: /^\d{5,}$/ },
      { key: "appSecret", label: "App secret", secret: true, required: true },
      { key: "systemUserToken", label: "System user access token", secret: true, required: true },
      { key: "embeddedSignupConfigId", label: "Embedded Signup configuration ID", secret: false, required: false, pattern: /^\d{5,}$/ },
      { key: "webhookVerifyToken", label: "Webhook verify token", secret: true, required: true },
      { key: "graphVersion", label: "Graph API version", secret: false, required: true, pattern: /^v\d+\.\d+$/, defaultValue: "v23.0" },
    ],
    async test(v) {
      const url = `https://graph.facebook.com/${v.graphVersion}/debug_token?input_token=${encodeURIComponent(v.systemUserToken!)}&access_token=${encodeURIComponent(`${v.appId}|${v.appSecret}`)}`;
      const r = await call(url, {});
      if (isResult(r)) return r;
      if (!r.ok) return httpFailure(r, "Meta debug_token");
      const d = ((await r.json()) as { data?: { is_valid?: boolean; app_id?: string } }).data;
      if (!d?.is_valid) return fail("AUTH_FAILED", "Meta reports the system user token as invalid");
      if (d.app_id !== v.appId) return fail("APP_MISMATCH", "Token belongs to a different app");
      return ok("App secret and system user token valid");
    },
    revokeHelp: "Revoke the system user token in Meta Business Settings → System users, and reset the app secret if exposed.",
  },
  {
    id: "TWILIO", name: "Twilio", description: "SMS/MMS and voice (missed calls).", docsUrl: "https://www.twilio.com/docs/iam/api-keys",
    environments: ["TEST", "LIVE"],
    fields: [
      { key: "accountSid", label: "Account SID", secret: false, required: true, pattern: /^AC[0-9a-f]{32}$/ },
      { key: "authToken", label: "Auth token", secret: true, required: true, pattern: /^[0-9a-f]{32}$/ },
    ],
    async test(v) {
      const r = await call(`https://api.twilio.com/2010-04-01/Accounts/${v.accountSid}.json`, {
        headers: { Authorization: `Basic ${Buffer.from(`${v.accountSid}:${v.authToken}`).toString("base64")}` } });
      if (isResult(r)) return r;
      if (!r.ok) return httpFailure(r, "Twilio account");
      const a = (await r.json()) as { status?: string; friendly_name?: string };
      return a.status === "active" ? ok("Account active", a.friendly_name) : fail("ACCOUNT_NOT_ACTIVE", `Account status: ${a.status}`);
    },
    revokeHelp: "Rotate the auth token in the Twilio Console → Account → API keys & tokens.",
  },
  {
    id: "AMAZON_SES", name: "Amazon SES", description: "Email sending for the platform and its customers.", docsUrl: "https://docs.aws.amazon.com/ses/",
    environments: ["TEST", "LIVE"],
    fields: [
      { key: "region", label: "Region", secret: false, required: true, pattern: /^[a-z]{2}-[a-z]+-\d$/, placeholder: "us-east-1" },
      { key: "accessKeyId", label: "Access key ID", secret: false, required: true, pattern: /^(AKIA|ASIA)[A-Z0-9]{16}$/ },
      { key: "secretAccessKey", label: "Secret access key", secret: true, required: true, pattern: /^[A-Za-z0-9/+=]{40}$/ },
      { key: "configurationSet", label: "Configuration set", secret: false, required: false },
      { key: "snsTopicArn", label: "SNS topic ARN (events)", secret: false, required: false, pattern: /^arn:aws:sns:/ },
    ],
    async test(v) {
      const url = new URL(`https://email.${v.region}.amazonaws.com/v2/email/account`);
      const r = await call(url.toString(), { headers: signGet(url, v.region!, "ses", v.accessKeyId!, v.secretAccessKey!) });
      if (isResult(r)) return r;
      if (!r.ok) return httpFailure(r, "SES GetAccount");
      const a = (await r.json()) as { ProductionAccessEnabled?: boolean; SendingEnabled?: boolean };
      return ok(`Credentials valid · sending ${a.SendingEnabled ? "enabled" : "disabled"} · ${a.ProductionAccessEnabled ? "production access" : "sandbox"}`);
    },
    revokeHelp: "Deactivate and delete the access key in AWS IAM → Users → Security credentials.",
  },
  {
    id: "FOCUS_NFE", name: "Focus NFe", description: "Brazilian NFS-e issuance.", docsUrl: "https://focusnfe.com.br/doc/",
    environments: ["TEST", "LIVE"],
    fields: [{ key: "token", label: "API token", secret: true, required: true }],
    async test(v, env) {
      const base = env === "LIVE" ? "https://api.focusnfe.com.br" : "https://homologacao.focusnfe.com.br";
      const r = await call(`${base}/v2/nfse/vendocas-connection-test`, { headers: { Authorization: `Basic ${Buffer.from(`${v.token}:`).toString("base64")}` } });
      if (isResult(r)) return r;
      // An unknown reference returns 404 only after authentication succeeded.
      if (r.status === 404 || r.ok) return ok(`Token accepted by ${env === "LIVE" ? "production" : "homologation"}`);
      return httpFailure(r, "Focus NFe");
    },
    revokeHelp: "Request a new token from Focus NFe support / painel and disable the old one.",
  },
  {
    id: "STORAGE_S3", name: "Cloudflare R2 / S3 storage", description: "Private bucket for media, imports and documents.",
    docsUrl: "https://developers.cloudflare.com/r2/api/s3/tokens/", environments: ["TEST", "LIVE"],
    fields: [
      { key: "endpoint", label: "Endpoint (empty for AWS S3)", secret: false, required: false, pattern: /^https:\/\/[^\s]+$/, placeholder: "https://<account>.r2.cloudflarestorage.com" },
      { key: "region", label: "Region", secret: false, required: true, defaultValue: "auto" },
      { key: "bucket", label: "Bucket", secret: false, required: true, pattern: /^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$/ },
      { key: "accessKeyId", label: "Access key ID", secret: false, required: true },
      { key: "secretAccessKey", label: "Secret access key", secret: true, required: true },
    ],
    async test(v) {
      const base = v.endpoint || `https://s3.${v.region}.amazonaws.com`;
      const url = new URL(`${base}/${v.bucket}?list-type=2&max-keys=1`);
      const r = await call(url.toString(), { headers: signGet(url, v.region || "auto", "s3", v.accessKeyId!, v.secretAccessKey!) });
      if (isResult(r)) return r;
      return r.ok ? ok("Bucket listable with these credentials") : httpFailure(r, "S3 ListObjectsV2");
    },
    revokeHelp: "Delete the API token / access key in the Cloudflare or AWS console.",
  },
  {
    id: "CUSTOM", name: "Other provider", description: "Placeholder for future integrations: named secret values, no automatic test.",
    docsUrl: "/docs/credentials-vault.md", environments: ["TEST", "LIVE"],
    fields: [
      { key: "label", label: "Provider name", secret: false, required: true },
      { key: "value1Name", label: "Field 1 name", secret: false, required: true },
      { key: "value1", label: "Field 1 value", secret: true, required: true },
      { key: "value2Name", label: "Field 2 name", secret: false, required: false },
      { key: "value2", label: "Field 2 value", secret: true, required: false },
    ],
    revokeHelp: "Revoke at the provider's console.",
  },
];

export const provider = (id: string) => PROVIDERS.find((p) => p.id === id);

import createClient, { type Middleware } from "openapi-fetch";
import type { paths } from "./schema";

export type { paths, components } from "./schema";

export interface ClientOptions {
  baseUrl: string;
  /** Returns the in-memory access token (never persisted to localStorage). */
  getAccessToken: () => string | null;
  /** Returns the active organization id; sent as X-Organization-Id. */
  getOrganizationId: () => string | null;
  /** Called on 401 to obtain a fresh access token via the refresh cookie. */
  refresh: () => Promise<string | null>;
}

/** Typed client generated from the OpenAPI contract (packages/contracts/openapi.json). */
export function createApiClient(opts: ClientOptions) {
  const client = createClient<paths>({ baseUrl: opts.baseUrl, credentials: "include" });
  const auth: Middleware = {
    async onRequest({ request }) {
      const token = opts.getAccessToken();
      if (token) request.headers.set("Authorization", `Bearer ${token}`);
      const org = opts.getOrganizationId();
      if (org) request.headers.set("X-Organization-Id", org);
      return request;
    },
  };
  client.use(auth);
  return client;
}

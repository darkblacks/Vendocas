import type { NextConfig } from "next";
import createNextIntlPlugin from "next-intl/plugin";

const withNextIntl = createNextIntlPlugin("./src/i18n/request.ts");

const securityHeaders = [
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
  { key: "X-Frame-Options", value: "DENY" },
  { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=()" },
];

const config: NextConfig = {
  // Docker image uses the standalone server; on Netlify the Next.js runtime packages the app itself.
  output: process.env.NETLIFY ? undefined : "standalone",
  poweredByHeader: false,
  transpilePackages: ["@vendocas/design-system", "@vendocas/api-client"],
  // The API is served under the same origin (/api/v1/*) so the refresh cookie is first-party. Proxied by the route
  // handler src/app/api/v1/[...path] (target read at request time — no rebuild needed when API_INTERNAL_URL changes).
  async headers() {
    return [{ source: "/:path*", headers: securityHeaders }];
  },
};

export default withNextIntl(config);

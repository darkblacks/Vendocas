import createMiddleware from "next-intl/middleware";
import { NextResponse, type NextRequest } from "next/server";
import { routing } from "./i18n/routing";
import { languageForCountry } from "./i18n/languages";

const intl = createMiddleware(routing);

/** Visitor's country from the hosting edge (Netlify, Vercel or Cloudflare). */
function country(req: NextRequest): string | undefined {
  const nf = req.headers.get("x-nf-geo");
  if (nf) {
    try { return (JSON.parse(atob(nf)) as { country?: { code?: string } }).country?.code; } catch { /* malformed header */ }
  }
  return req.headers.get("x-country") ?? req.headers.get("x-vercel-ip-country") ?? req.headers.get("cf-ipcountry") ?? undefined;
}

/**
 * First visit without a language in the URL and no saved choice: the language of the visitor's country
 * (Canada → en, Mexico → es, Portugal → pt, Italy → it...). A language picked in the switcher (NEXT_LOCALE cookie) wins.
 */
export default function proxy(req: NextRequest) {
  const { pathname } = req.nextUrl;
  const hasLocale = routing.locales.some((l) => pathname === `/${l}` || pathname.startsWith(`/${l}/`));
  if (!hasLocale && !req.cookies.get("NEXT_LOCALE")) {
    const cc = country(req);
    if (cc) {
      const url = req.nextUrl.clone();
      url.pathname = `/${languageForCountry(cc)}${pathname === "/" ? "" : pathname}`;
      return NextResponse.redirect(url);
    }
  }
  return intl(req);
}

export const config = {
  matcher: ["/((?!api|_next|_vercel|sdk|.*\\..*).*)"],
};

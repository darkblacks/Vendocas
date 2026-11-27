import type { Metadata, Viewport } from "next";
import type { ReactNode } from "react";
import { Poppins } from "next/font/google";
import "./globals.css";

const poppins = Poppins({ subsets: ["latin", "latin-ext"], weight: ["400", "500", "600", "700"], variable: "--font-poppins", display: "swap" });

export const metadata: Metadata = {
  title: { default: "Vendocas Control Center", template: "%s · Vendocas Control" },
  robots: { index: false, follow: false },
};

// Explicit device-width viewport; user zoom stays enabled (accessibility) — the iOS auto-zoom is prevented in CSS.
export const viewport: Viewport = { width: "device-width", initialScale: 1, viewportFit: "cover", themeColor: "#03050F" };

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    // The operator console uses the brand's dark theme (midnight + orange/blue).
    <html lang="pt-BR" className={`dark ${poppins.variable}`}>
      <body className="min-h-screen bg-background font-sans antialiased">{children}</body>
    </html>
  );
}

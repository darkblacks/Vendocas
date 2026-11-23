import type { Metadata, Viewport } from "next";
import type { ReactNode } from "react";
import "./globals.css";

export const metadata: Metadata = {
  title: { default: "Vendocas Control Center", template: "%s · Vendocas Control" },
  robots: { index: false, follow: false },
};

// Explicit device-width viewport; user zoom stays enabled (accessibility) — the iOS auto-zoom is prevented in CSS.
export const viewport: Viewport = { width: "device-width", initialScale: 1, viewportFit: "cover" };

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="pt-BR">
      <body className="min-h-screen bg-background font-sans antialiased">{children}</body>
    </html>
  );
}

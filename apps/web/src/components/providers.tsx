"use client";
import * as React from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ThemeProvider } from "next-themes";
import { AuthProvider } from "@/lib/auth";
import { ApiError, UNAVAILABLE } from "@/lib/api";
import { BackendBanner } from "@/components/backend-banner";

export function Providers({ children }: { children: React.ReactNode }) {
  const [client] = React.useState(() => new QueryClient({ defaultOptions: { queries: {
    staleTime: 15_000, refetchOnWindowFocus: false,
    // Never retry while the API is down: the banner's single backoff probe handles recovery.
    retry: (count, e) => !(e instanceof ApiError && e.code === UNAVAILABLE) && count < 1,
  } } }));
  return (
    <ThemeProvider attribute="class" defaultTheme="system" enableSystem>
      <QueryClientProvider client={client}>
        <BackendBanner />
        <AuthProvider>{children}</AuthProvider>
      </QueryClientProvider>
    </ThemeProvider>
  );
}

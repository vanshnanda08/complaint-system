"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState } from "react";
import { AuthProvider } from "@/lib/auth";
import { ComposerProvider } from "@/lib/composer";

/**
 * Client-side providers.
 *
 * The QueryClient is created inside component state rather than at module
 * scope. At module scope it would be shared across requests on the server,
 * which leaks one user's cached data into another user's render -- the classic
 * way this goes wrong.
 */
export function Providers({ children }: { children: React.ReactNode }) {
  const [client] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            // A citizen outdoors on a weak connection retries by hand; three
            // silent retries just make the failure take longer to appear.
            retry: 1,
            refetchOnWindowFocus: false,
          },
        },
      }),
  );

  return (
    <QueryClientProvider client={client}>
      <AuthProvider>
        <ComposerProvider>{children}</ComposerProvider>
      </AuthProvider>
    </QueryClientProvider>
  );
}

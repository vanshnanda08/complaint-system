"use client";

import { Suspense, useEffect } from "react";
import { useSearchParams } from "next/navigation";
import { PageShell } from "@/components/PageShell";
import { useSignIn } from "@/lib/signInDialog";
import { useAuth } from "@/lib/auth";

/**
 * `/register` opens the same dialog on its register panel.
 *
 * Sign in and register were two routes and two forms differing by one field.
 * They are one panel now -- see SignInDialog -- because somebody who mistyped a
 * password and somebody who has no account are one tap apart, and making that a
 * page load was the wrong shape for the failure.
 */
function RegisterInner() {
  const params = useSearchParams();
  const { openSignIn } = useSignIn();
  const { session, initialising } = useAuth();
  const next = params.get("next") ?? "/";

  useEffect(() => {
    if (initialising || session) return;
    openSignIn({ mode: "up", next });
  }, [initialising, session, openSignIn, next]);

  return (
    <PageShell>
      <h1 className="text-display">Create an account</h1>
      <p className="mt-3 text-body" style={{ maxWidth: "var(--measure-prose)" }}>
        {session
          ? "You are already signed in."
          : "The panel is open. Reporting a problem needs no account at all — an account only lets you follow what you reported and confirm a fix."}
      </p>
      {!session && !initialising && (
        <p className="mt-4">
          <button
            type="button"
            className="text-body underline text-ink bg-transparent border-0 p-0 cursor-pointer"
            onClick={() => openSignIn({ mode: "up", next })}
          >
            Open it again
          </button>
        </p>
      )}
    </PageShell>
  );
}

export default function RegisterPage() {
  return (
    <Suspense
      fallback={
        <PageShell>
          <h1 className="text-display">Create an account</h1>
        </PageShell>
      }
    >
      <RegisterInner />
    </Suspense>
  );
}

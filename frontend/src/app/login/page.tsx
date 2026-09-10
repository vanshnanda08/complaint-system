"use client";

import { Suspense, useEffect } from "react";
import { useSearchParams } from "next/navigation";
import { PageShell } from "@/components/PageShell";
import { useSignIn } from "@/lib/signInDialog";
import { useAuth } from "@/lib/auth";

/**
 * `/login` is the dialog, not a second form.
 *
 * WHY THE ROUTE STILL EXISTS. A staff link like `/staff/issues/<id>` sent to
 * somebody signed out has to land somewhere, and "somewhere" cannot be a modal
 * on a page they were never on. So the route stays as the entry point for
 * people ARRIVING at the site; the dialog serves people already using it.
 *
 * WHY IT NO LONGER HAS ITS OWN FORM. It used to, and the sign-in dialog is
 * mounted globally -- so this page carried two fields labelled "Email", two
 * labelled "Password", and two submit buttons, one set of which was invisible.
 * A closed `<dialog>` is `display: none`, so no user ever saw the duplicate;
 * the route sweep did, and a duplicated form is a duplicated bug regardless of
 * who can see it.
 *
 * `?next=` is honoured on SUCCESS only. Somebody who opens this and changes
 * their mind should not be thrown at a staff page they cannot read.
 */
function LoginInner() {
  const params = useSearchParams();
  const { openSignIn } = useSignIn();
  const { session, initialising } = useAuth();
  const next = params.get("next") ?? "/";

  useEffect(() => {
    // Wait for the refresh check. Opening a sign-in dialog at somebody who is
    // already signed in is the site failing to notice them.
    if (initialising || session) return;
    openSignIn({ mode: "in", next });
  }, [initialising, session, openSignIn, next]);

  return (
    <PageShell>
      <h1 className="text-display">Sign in</h1>
      <p className="mt-3 text-body" style={{ maxWidth: "var(--measure-prose)" }}>
        {session
          ? "You are already signed in."
          : "The sign-in panel is open. You do not need an account to report a problem — only to follow what you reported."}
      </p>
      {!session && !initialising && (
        <p className="mt-4">
          <button
            type="button"
            className="text-body underline text-ink bg-transparent border-0 p-0 cursor-pointer"
            onClick={() => openSignIn({ mode: "in", next })}
          >
            Open it again
          </button>
        </p>
      )}
    </PageShell>
  );
}

export default function LoginPage() {
  // useSearchParams needs a Suspense boundary or the build fails.
  return (
    <Suspense
      fallback={
        <PageShell>
          <h1 className="text-display">Sign in</h1>
        </PageShell>
      }
    >
      <LoginInner />
    </Suspense>
  );
}

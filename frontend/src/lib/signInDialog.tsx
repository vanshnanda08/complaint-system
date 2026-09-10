"use client";

import { createContext, useCallback, useContext, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { SignInDialog } from "@/components/SignInDialog";

/**
 * One sign-in dialog for the whole application, openable from anywhere.
 *
 * WHY A CONTEXT RATHER THAN A DIALOG PER CALLER. Several places need to ask
 * somebody to sign in: the nav, the empty state on My reports, the empty state
 * on the work queue, the line in the report composer. Each mounting its own
 * `<dialog>` means several dialogs in the DOM, several pieces of duplicated
 * open/close state, and -- the part that actually breaks -- the possibility of
 * two being open at once, which the top layer will happily allow and which
 * traps focus in whichever opened last.
 *
 * One dialog, mounted once by the provider, opened by a function.
 *
 * `/login` and `/register` still exist as routes and are NOT redundant. A link
 * like `/staff/issues/<id>` sent to somebody who is not signed in has to land
 * somewhere, and "somewhere" cannot be a modal on a page they were never on.
 * The dialog is the path for people already using the site; the routes are the
 * path for people arriving at it.
 */
export interface SignInOptions {
  /** Open on the register panel rather than sign-in. */
  mode?: "in" | "up";
  /** Where to go once it succeeds. Defaults to staying put. */
  next?: string;
}

interface SignInContext {
  openSignIn: (opts?: SignInOptions) => void;
}

const Ctx = createContext<SignInContext | null>(null);

export function SignInProvider({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [opts, setOpts] = useState<SignInOptions>({});

  const openSignIn = useCallback((o?: SignInOptions) => {
    setOpts(o ?? {});
    setOpen(true);
  }, []);
  const close = useCallback(() => setOpen(false), []);

  // Only on success, and only when the caller asked for it. Closing without
  // signing in must not navigate: somebody who opened the dialog from /issues
  // and changed their mind belongs back on /issues, not wherever a `next`
  // parameter happened to point.
  const succeeded = useCallback(() => {
    setOpen(false);
    if (opts.next) router.replace(opts.next);
  }, [opts.next, router]);

  const value = useMemo(() => ({ openSignIn }), [openSignIn]);

  return (
    <Ctx.Provider value={value}>
      {children}
      <SignInDialog
        open={open}
        mode={opts.mode ?? "in"}
        onClose={close}
        onSuccess={succeeded}
      />
    </Ctx.Provider>
  );
}

export function useSignIn(): SignInContext {
  const ctx = useContext(Ctx);
  if (!ctx) {
    throw new Error("useSignIn must be used inside SignInProvider");
  }
  return ctx;
}

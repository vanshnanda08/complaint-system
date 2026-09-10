"use client";

import { createContext, useCallback, useContext, useMemo, useState } from "react";
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
interface SignInContext {
  openSignIn: () => void;
}

const Ctx = createContext<SignInContext | null>(null);

export function SignInProvider({ children }: { children: React.ReactNode }) {
  const [open, setOpen] = useState(false);
  const openSignIn = useCallback(() => setOpen(true), []);
  const close = useCallback(() => setOpen(false), []);
  const value = useMemo(() => ({ openSignIn }), [openSignIn]);

  return (
    <Ctx.Provider value={value}>
      {children}
      <SignInDialog open={open} onClose={close} />
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

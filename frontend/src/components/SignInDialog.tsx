"use client";

import { useEffect, useId, useRef, useState } from "react";
import { useAuth } from "@/lib/auth";
import { Button } from "@/components/Button";
import { TextField } from "@/components/TextField";

/**
 * Signing in, as a dialog rather than a route.
 *
 * WHY NOT A PAGE. `/login` and `/register` were two routes whose entire job was
 * to take you away from whatever you were doing, collect two fields, and send
 * you back. On a layout whose argument is that nobody should have to navigate,
 * that is the one navigation nobody chose. It is also the worst one to make a
 * citizen do: they arrived to report a pothole, and the site answered by
 * changing the subject.
 *
 * So it is a `<dialog>` opened from the app bar. Reporting still needs no
 * account at all, and the copy says so at the point of asking.
 *
 * `<dialog showModal()>` rather than a hand-built overlay, because the platform
 * already does the four things a hand-built one forgets: it traps focus, it
 * closes on Escape, it renders in the top layer above everything regardless of
 * z-index, and it marks the rest of the page inert for assistive technology.
 *
 * SIGN IN AND REGISTER ARE ONE DIALOG. They differ by a single field, and a
 * person who mistyped their password and a person who has no account are one
 * tap apart -- which is the actual failure this collapses.
 */
export function SignInDialog({
  open,
  onClose,
  onSuccess,
  mode: initialMode = "in",
}: {
  open: boolean;
  onClose: () => void;
  /** Called only when sign-in or registration actually succeeded. */
  onSuccess?: () => void;
  mode?: "in" | "up";
}) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const { signIn, register } = useAuth();
  const titleId = useId();

  const [mode, setMode] = useState<"in" | "up">(initialMode);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [fullName, setFullName] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // showModal() is imperative, so opening has to be an effect. Calling it on an
  // already-open dialog throws, hence the guard.
  //
  // Opening also RESETS the panel. Without this, closing while on the register
  // tab and reopening later hands the user a "Create an account" form they did
  // not ask for -- and, worse, a stale error from the last attempt. A dialog
  // that remembers its last state is a dialog that lies about why it opened.
  useEffect(() => {
    const d = dialogRef.current;
    if (!d) return;
    if (open && !d.open) {
      setMode(initialMode);
      setError(null);
      setPassword("");
      d.showModal();
    }
    if (!open && d.open) d.close();
  }, [open, initialMode]);

  // Escape and the backdrop both fire `close`; the parent owns the state, so it
  // has to hear about either.
  useEffect(() => {
    const d = dialogRef.current;
    if (!d) return;
    const handler = () => onClose();
    d.addEventListener("close", handler);
    return () => d.removeEventListener("close", handler);
  }, [onClose]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      if (mode === "in") await signIn(email, password);
      else await register({ email, password, fullName });
      // Success and dismissal are different events. Only this one may navigate.
      if (onSuccess) onSuccess();
      else onClose();
    } catch (err) {
      // The server's own words where it gave any. It knows why it refused and
      // this component does not.
      const detail =
        (err as { problem?: { detail?: string } })?.problem?.detail ??
        (err as Error)?.message;
      setError(
        detail && detail.length < 200
          ? detail
          : mode === "in"
            ? "That email and password did not match. Check them and try again."
            : "That account could not be created. The email may already be registered.",
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <dialog
      ref={dialogRef}
      aria-labelledby={titleId}
      className="ct-dialog"
      // A click that lands on the dialog element itself is a backdrop click:
      // its children cover the whole panel, so anything inside hits them first.
      onClick={(e) => {
        if (e.target === dialogRef.current) onClose();
      }}
    >
      <form
        method="dialog"
        onSubmit={submit}
        className="bg-surface-raised border border-rule-strong p-6 flex flex-col gap-4"
        style={{ borderRadius: "var(--radius)", width: "min(430px, calc(100vw - 32px))" }}
      >
        <div className="flex items-start justify-between gap-4">
          <h2 id={titleId} className="text-heading">
            {mode === "in" ? "Sign in" : "Create an account"}
          </h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="text-ink-faint hover:text-ink cursor-pointer bg-transparent border-0 leading-none px-2 py-1 rounded"
            style={{ fontSize: 20 }}
          >
            ×
          </button>
        </div>

        <p className="text-dense text-ink-muted">
          You never need an account to report a problem. Signing in lets you follow what
          you reported — and, if you work for the corporation, do something about it.
        </p>

        {mode === "up" && (
          <TextField
            label="Your name"
            autoComplete="name"
            value={fullName}
            onChange={(e) => setFullName(e.target.value)}
          />
        )}

        <TextField
          label="Email"
          type="email"
          autoComplete="username"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
        />
        <TextField
          label="Password"
          type="password"
          autoComplete={mode === "in" ? "current-password" : "new-password"}
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />

        {error && (
          <p className="text-dense" style={{ color: "var(--st-breached)" }} role="alert">
            {error}
          </p>
        )}

        <Button type="submit" disabled={busy}>
          {busy
            ? mode === "in"
              ? "Signing in…"
              : "Creating…"
            : mode === "in"
              ? "Sign in"
              : "Create account"}
        </Button>

        <p className="text-dense text-ink-muted">
          {mode === "in" ? "No account? " : "Already registered? "}
          <button
            type="button"
            className="underline text-ink bg-transparent border-0 p-0 cursor-pointer text-dense"
            onClick={() => {
              setMode(mode === "in" ? "up" : "in");
              setError(null);
            }}
          >
            {mode === "in" ? "Create one" : "Sign in instead"}
          </button>
          . Or report a problem without either.
        </p>
      </form>
    </dialog>
  );
}

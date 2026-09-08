"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { PageShell } from "@/components/PageShell";
import { Button } from "@/components/Button";
import { TextField } from "@/components/TextField";
import { LoadingState } from "@/components/LoadingState";
import { useAuth } from "@/lib/auth";
import { ApiError } from "@/lib/api";
import { loginSchema, type LoginValues } from "@/lib/schemas";

/**
 * Sign in (blueprint §3.10).
 *
 * Single column, 44ch, email and password. Nothing else: no social login, no
 * marketing copy, no split-screen image.
 *
 * ONE error message for bad credentials, deliberately not distinguishing an
 * unknown account from a wrong password. The backend already refuses to
 * distinguish them -- `InvalidCredentialsException` covers unknown account,
 * wrong password, disabled account and an account seeded without a password --
 * because doing otherwise is a free account-enumeration oracle. This screen
 * renders the server's sentence verbatim rather than adding its own.
 *
 * No `<form>` posting to the server; an event handler (blueprint §6). The form
 * element is still a form for the keyboard's sake -- Enter should submit -- but
 * onSubmit is prevented.
 */
function LoginInner() {
  const router = useRouter();
  const params = useSearchParams();
  const { signIn } = useAuth();

  const [error, setError] = useState<string | null>(null);
  const next = params.get("next") ?? "/me/reports";

  const { register, handleSubmit, formState } = useForm<LoginValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: { email: "", password: "" },
  });

  const submit = handleSubmit(async ({ email, password }) => {
    setError(null);
    try {
      await signIn(email, password);
      router.push(next);
    } catch (err) {
      setError(
        err instanceof ApiError
          ? (err.problem?.detail ?? "Those details were not accepted.")
          : "Could not reach the server. Check your connection.",
      );
    }
  });

  return (
    <PageShell>
      <div style={{ maxWidth: "var(--measure-form)" }}>
        <h1 className="text-display">Sign in</h1>

        <form onSubmit={submit} noValidate className="mt-6 flex flex-col gap-5">
          <TextField
            label="Email"
            type="email"
            autoComplete="email"
            error={formState.errors.email?.message}
            {...register("email")}
          />
          <TextField
            label="Password"
            type="password"
            autoComplete="current-password"
            error={formState.errors.password?.message}
            {...register("password")}
          />

          {error && (
            <p className="text-meta" style={{ color: "var(--st-breached)" }} role="alert">
              {error}
            </p>
          )}

          <Button type="submit" disabled={formState.isSubmitting}>
            {formState.isSubmitting ? "Signing in…" : "Sign in"}
          </Button>
        </form>

        <p className="mt-6 text-dense">
          No account?{" "}
          <Link href="/register" className="underline text-ink">
            Create one
          </Link>
          . You can report a problem without one.
        </p>
      </div>
    </PageShell>
  );
}

/**
 * `useSearchParams` opts the subtree into client-side rendering, so Next
 * requires a Suspense boundary around it -- without one the whole route
 * refuses to prerender. The boundary is here rather than higher up so the
 * header and page frame still render server-side while the filters resolve.
 */
export default function LoginPage() {
  return (
    <Suspense
      fallback={
        <PageShell>
          <LoadingState label="Loading" />
        </PageShell>
      }
    >
      <LoginInner />
    </Suspense>
  );
}

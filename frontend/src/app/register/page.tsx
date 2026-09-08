"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { PageShell } from "@/components/PageShell";
import { Button } from "@/components/Button";
import { TextField } from "@/components/TextField";
import { useAuth } from "@/lib/auth";
import { ApiError } from "@/lib/api";
import { registerSchema, type RegisterValues } from "@/lib/schemas";

/**
 * Create account (blueprint §3.10). Same shape as sign in, one extra field.
 *
 * The copy states what an account is actually for, because the honest answer
 * is narrow: reporting works without one, and the only thing an account adds
 * is the ability to be asked whether a fix worked (DD-006, DD-017).
 */
export default function RegisterPage() {
  const router = useRouter();
  const { register } = useAuth();

  const [error, setError] = useState<string | null>(null);

  const { register: field, handleSubmit, formState } = useForm<RegisterValues>({
    resolver: zodResolver(registerSchema),
    defaultValues: { fullName: "", email: "", password: "" },
  });

  const submit = handleSubmit(async (values) => {
    setError(null);
    try {
      await register(values);
      router.push("/me/reports");
    } catch (err) {
      setError(
        err instanceof ApiError
          ? (err.problem?.detail ?? "That account could not be created.")
          : "Could not reach the server. Check your connection.",
      );
    }
  });

  return (
    <PageShell>
      <div style={{ maxWidth: "var(--measure-form)" }}>
        <h1 className="text-display">Create an account</h1>
        <p className="mt-2 text-dense text-ink-muted">
          You do not need one to report a problem. An account lets the system ask
          you whether a fix actually worked.
        </p>

        <form onSubmit={submit} noValidate className="mt-6 flex flex-col gap-5">
          <TextField
            label="Full name"
            autoComplete="name"
            error={formState.errors.fullName?.message}
            {...field("fullName")}
          />
          <TextField
            label="Email"
            type="email"
            autoComplete="email"
            error={formState.errors.email?.message}
            {...field("email")}
          />
          <TextField
            label="Password"
            type="password"
            autoComplete="new-password"
            hint="At least 8 characters."
            error={formState.errors.password?.message}
            {...field("password")}
          />

          {error && (
            <p className="text-meta" style={{ color: "var(--st-breached)" }} role="alert">
              {error}
            </p>
          )}

          <Button type="submit" disabled={formState.isSubmitting}>
            {formState.isSubmitting ? "Creating…" : "Create account"}
          </Button>
        </form>

        <p className="mt-6 text-dense">
          Already have one?{" "}
          <Link href="/login" className="underline text-ink">
            Sign in
          </Link>
          .
        </p>
      </div>
    </PageShell>
  );
}

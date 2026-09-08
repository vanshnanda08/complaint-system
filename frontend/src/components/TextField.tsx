"use client";

import { useId } from "react";
import type { InputHTMLAttributes } from "react";

/**
 * A labelled input.
 *
 * Every input has a real associated label -- blueprint 8 makes that
 * non-negotiable -- so the label is a required prop rather than something a
 * caller can forget. There is no placeholder-as-label option, because a
 * placeholder disappears the moment somebody starts typing, which is exactly
 * when a person filling a form outdoors needs to re-read what the field was.
 *
 * The error is red, and this is the one non-status use of a status colour in
 * the system. It is deliberate: `--st-breached` is the palette's "something is
 * wrong" and inventing a separate error red would be a ninth saturated colour
 * for a meaning the system already has a colour for. The error also carries a
 * word, never colour alone, and is wired with aria-describedby.
 */

export interface TextFieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, "id"> {
  label: string;
  hint?: string;
  error?: string;
}

export function TextField({ label, hint, error, className = "", ...rest }: TextFieldProps) {
  const id = useId();
  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;
  const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(" ");

  return (
    <div className="flex flex-col gap-1" style={{ maxWidth: "var(--measure-form)" }}>
      <label htmlFor={id} className="text-meta text-ink">
        {label}
      </label>

      {hint && (
        <p id={hintId} className="text-meta text-ink-muted">
          {hint}
        </p>
      )}

      <input
        id={id}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy || undefined}
        className={`w-full bg-surface-raised px-3 text-body text-ink border disabled:opacity-45 disabled:cursor-not-allowed ${className}`}
        style={{
          minHeight: "var(--hit-min)",
          borderRadius: "var(--radius)",
          borderColor: error ? "var(--st-breached)" : "var(--rule)",
          borderWidth: error ? 2 : 1,
        }}
        {...rest}
      />

      {error && (
        <p id={errorId} className="text-meta" style={{ color: "var(--st-breached)" }}>
          {error}
        </p>
      )}
    </div>
  );
}

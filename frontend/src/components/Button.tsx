"use client";

import type { ButtonHTMLAttributes } from "react";

/**
 * The only button.
 *
 * No colour. Colour means status (blueprint 1.2), and a button is not a status,
 * so emphasis is carried by weight and fill in ink rather than by a brand
 * accent. `primary` is ink-filled, `secondary` is ink-outlined, and where two
 * actions must read as equally weighted -- the verification screen's "Fixed"
 * and "Not fixed" -- both use `secondary`, because making one primary would
 * bias the measurement the project depends on.
 *
 * Minimum height is --hit-min (48px): one-handed outdoor use, blueprint 8.
 */

type Variant = "primary" | "secondary";

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
}

export function Button({ variant = "primary", className = "", ...rest }: ButtonProps) {
  const base =
    "inline-flex items-center justify-center gap-2 px-4 text-dense font-medium " +
    "cursor-pointer transition-none " +
    // Disabled is genuinely non-interactive, not merely faded: a control that
    // looks disabled and still fires is worse than one that looks enabled.
    "disabled:cursor-not-allowed disabled:opacity-45";

  const skin =
    variant === "primary"
      ? "bg-ink text-surface-raised border border-ink"
      : "bg-surface-raised text-ink border border-ink";

  return (
    <button
      className={`${base} ${skin} ${className}`}
      style={{ minHeight: "var(--hit-min)", borderRadius: "var(--radius)" }}
      {...rest}
    />
  );
}

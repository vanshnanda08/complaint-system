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
 *
 * The only motion is a press: `u-press` scales to 0.97 while held. Hover gets
 * a small opacity or fill shift and nothing else -- a button that slides or
 * grows on hover moves the target out from under a shaky hand, which is the
 * opposite of what an outdoor one-handed control wants.
 */

type Variant = "primary" | "secondary";

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
}

export function Button({ variant = "primary", className = "", ...rest }: ButtonProps) {
  const base =
    "inline-flex items-center justify-center gap-2 px-4 text-dense font-medium " +
    // u-press scales to 0.97 on :active. On a phone there is no hover, so the
    // press is the ONLY feedback between tapping and the page changing --
    // which on a weak connection can be a second or more of apparent nothing.
    "cursor-pointer u-press " +
    // Disabled is genuinely non-interactive, not merely faded: a control that
    // looks disabled and still fires is worse than one that looks enabled.
    "disabled:cursor-not-allowed disabled:opacity-45 disabled:active:scale-100";

  const skin =
    variant === "primary"
      ? "bg-ink text-surface-raised border border-ink hover:opacity-90"
      : "bg-surface-raised text-ink border border-ink hover:bg-surface";

  return (
    <button
      className={`${base} ${skin} ${className}`}
      style={{ minHeight: "var(--hit-min)", borderRadius: "var(--radius)" }}
      {...rest}
    />
  );
}

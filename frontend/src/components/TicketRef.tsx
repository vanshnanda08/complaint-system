"use client";

import { useState } from "react";
import Link from "next/link";

/**
 * A ticket reference.
 *
 * Mono, because a reference is read aloud over a phone, compared digit by
 * digit, and pasted into a WhatsApp message. Fixed-width serves all three, and
 * the monospace is scoped to exactly this and to coordinates (blueprint §1.4).
 *
 * The copy control confirms in words rather than with a toast: blueprint §1.7
 * rules out toasts for anything reversible, and the label changing to "Copied"
 * is both the confirmation and the thing a screen reader announces.
 */

export interface TicketRefProps {
  publicRef: string;
  issueId?: string;
  size?: "inline" | "large";
  copyable?: boolean;
}

export function TicketRef({ publicRef, issueId, size = "inline", copyable = false }: TicketRefProps) {
  const [copied, setCopied] = useState(false);

  const text = (
    <span
      className="font-mono"
      style={size === "large" ? { fontSize: "var(--size-display)", lineHeight: "var(--lh-display)" } : undefined}
    >
      {publicRef}
    </span>
  );

  return (
    <span className="inline-flex items-center gap-3">
      {issueId ? (
        <Link href={`/issues/${issueId}`} className="text-ink no-underline hover:underline">
          {text}
        </Link>
      ) : (
        text
      )}

      {copyable && (
        <button
          type="button"
          className="text-meta text-ink underline cursor-pointer bg-transparent border-0 p-2"
          onClick={async () => {
            try {
              await navigator.clipboard.writeText(publicRef);
              setCopied(true);
              window.setTimeout(() => setCopied(false), 4000);
            } catch {
              // Clipboard access can be refused (insecure context, permission
              // policy). Saying so is better than a button that does nothing:
              // the reference is on screen and can be copied by hand.
              setCopied(false);
            }
          }}
        >
          {copied ? "Copied" : "Copy"}
        </button>
      )}
    </span>
  );
}

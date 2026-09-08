"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useAuth } from "@/lib/auth";

/**
 * The sticky header.
 *
 * Carries the one shadow permitted in the entire system -- a 1px rule
 * underneath (blueprint §1.7). Not a drop shadow: a hairline.
 *
 * Staff links appear only for staff. That is a convenience, not a security
 * boundary: every staff endpoint is guarded server-side by role AND by
 * `@issueGuard`, so hiding the link stops a citizen wondering what it is, and
 * nothing more.
 */
const PUBLIC_LINKS = [
  { href: "/map", label: "Map" },
  { href: "/issues", label: "Issues" },
  { href: "/dashboard", label: "Dashboard" },
];

export function SiteHeader() {
  const { session, signOut, initialising } = useAuth();
  const pathname = usePathname();
  const isStaff = session && session.role !== "CITIZEN";

  return (
    <header
      className="sticky top-0 z-[500] bg-surface-raised border-b border-rule"
      style={{ boxShadow: "none" }}
    >
      <nav
        className="mx-auto flex flex-wrap items-center gap-x-5 gap-y-1 px-4 py-2"
        style={{ maxWidth: 1100 }}
        aria-label="Main"
      >
        <Link href="/" className="text-ink no-underline flex items-center gap-2">
          <span
            aria-hidden="true"
            style={{ width: 3, height: 18, background: "var(--ink)", display: "inline-block" }}
          />
          <span className="text-heading">CivicTrack</span>
        </Link>

        {PUBLIC_LINKS.map((l) => (
          <Link
            key={l.href}
            href={l.href}
            className="text-dense text-ink no-underline"
            style={{
              // Current page marked by weight and an underline, not by colour.
              fontWeight: pathname.startsWith(l.href) ? 600 : 400,
              textDecoration: pathname.startsWith(l.href) ? "underline" : "none",
              textUnderlineOffset: 4,
            }}
          >
            {l.label}
          </Link>
        ))}

        {isStaff && (
          <Link href="/staff/queue" className="text-dense text-ink no-underline">
            Work queue
          </Link>
        )}

        <span className="ml-auto flex items-center gap-4">
          {!initialising && session && (
            <>
              <Link href="/me/reports" className="text-meta text-ink no-underline">
                {session.fullName}
              </Link>
              <button
                type="button"
                onClick={() => void signOut()}
                className="text-meta text-ink underline bg-transparent border-0 cursor-pointer p-2"
              >
                Sign out
              </button>
            </>
          )}
          {!initialising && !session && (
            <Link href="/login" className="text-meta text-ink no-underline underline">
              Sign in
            </Link>
          )}
          <Link
            href="/report"
            className="inline-flex items-center px-3 bg-ink text-surface-raised text-dense no-underline"
            style={{ minHeight: 40, borderRadius: "var(--radius)" }}
          >
            Report a problem
          </Link>
        </span>
      </nav>
    </header>
  );
}

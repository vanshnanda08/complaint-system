"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { useTheme, useThemeSync, toggleTheme } from "@/lib/theme";
import { useState } from "react";
import { useSignIn } from "@/lib/signInDialog";

/**
 * The primary navigation.
 *
 * A sidebar from `md` up, a collapsing bar below it. The two are one element:
 * on a phone the sidebar's own contents would be a logo plus six stacked
 * full-width rows, and with `sticky top-0` that is most of a 360px viewport
 * spent on navigation before any content is reached.
 *
 * Staff links appear only for staff. That is a convenience, not a security
 * boundary: every staff endpoint is guarded server-side by role AND by
 * `@issueGuard`, so hiding the link stops a citizen wondering what it is, and
 * nothing more.
 *
 * The current page is marked by weight and background, and the background is
 * `--brand-dark`, never a status colour. Navigation is not a status.
 */
interface NavLink {
  href: string;
  label: string;
  icon: IconName;
  staffOnly?: boolean;
}

const MENU_LINKS: NavLink[] = [
  { href: "/dashboard", label: "Dashboard", icon: "grid" },
  { href: "/issues", label: "Issues", icon: "list" },
  { href: "/map", label: "Map", icon: "pin" },
  { href: "/report", label: "Report a problem", icon: "flag" },
  // "Work queue", not "Team". The link goes to a queue of work items; the
  // blueprint's vocabulary rule (§9) is that the label names the thing.
  { href: "/staff/queue", label: "Work queue", icon: "inbox", staffOnly: true },
];

type IconName = "grid" | "list" | "pin" | "flag" | "inbox" | "signOut" | "signIn";

/** Decorative throughout: every icon here sits beside its own text label. */
function Icon({ name }: { name: IconName }) {
  const common = {
    width: 20,
    height: 20,
    viewBox: "0 0 24 24",
    fill: "none",
    stroke: "currentColor",
    strokeWidth: 2,
    strokeLinecap: "round" as const,
    strokeLinejoin: "round" as const,
    "aria-hidden": true,
    focusable: false,
  };
  switch (name) {
    case "grid":
      return (
        <svg {...common}>
          <rect x="3" y="3" width="7" height="9" rx="1" />
          <rect x="14" y="3" width="7" height="5" rx="1" />
          <rect x="14" y="12" width="7" height="9" rx="1" />
          <rect x="3" y="16" width="7" height="5" rx="1" />
        </svg>
      );
    case "list":
      return (
        <svg {...common}>
          <line x1="8" y1="6" x2="21" y2="6" />
          <line x1="8" y1="12" x2="21" y2="12" />
          <line x1="8" y1="18" x2="21" y2="18" />
          <line x1="3" y1="6" x2="3.01" y2="6" />
          <line x1="3" y1="12" x2="3.01" y2="12" />
          <line x1="3" y1="18" x2="3.01" y2="18" />
        </svg>
      );
    case "pin":
      return (
        <svg {...common}>
          <path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0118 0z" />
          <circle cx="12" cy="10" r="3" />
        </svg>
      );
    case "flag":
      return (
        <svg {...common}>
          <path d="M4 15s1-1 4-1 5 2 8 2 4-1 4-1V3s-1 1-4 1-5-2-8-2-4 1-4 1z" />
          <line x1="4" y1="22" x2="4" y2="15" />
        </svg>
      );
    case "inbox":
      return (
        <svg {...common}>
          <polyline points="22 12 16 12 14 15 10 15 8 12 2 12" />
          <path d="M5.45 5.11L2 12v6a2 2 0 002 2h16a2 2 0 002-2v-6l-3.45-6.89A2 2 0 0016.76 4H7.24a2 2 0 00-1.79 1.11z" />
        </svg>
      );
    case "signOut":
      return (
        <svg {...common}>
          <path d="M9 21H5a2 2 0 01-2-2V5a2 2 0 012-2h4" />
          <polyline points="16 17 21 12 16 7" />
          <line x1="21" y1="12" x2="9" y2="12" />
        </svg>
      );
    case "signIn":
      return (
        <svg {...common}>
          <path d="M15 3h4a2 2 0 012 2v14a2 2 0 01-2 2h-4" />
          <polyline points="10 17 15 12 10 7" />
          <line x1="15" y1="12" x2="3" y2="12" />
        </svg>
      );
  }
}

/**
 * Shared row shape. Deliberately carries NO background utility.
 *
 * It used to include `bg-transparent`, so that the <button> rows would not
 * show a user-agent button background. That silently broke the active link:
 * the active state appends `bg-brand-dark`, but which of two background
 * utilities wins is decided by their order in the stylesheet, not by their
 * order in the class attribute -- and `bg-transparent` came later. The active
 * item rendered white-on-white and vanished, in light mode only, while the
 * build stayed green.
 *
 * Each caller now sets exactly one background, so there is nothing to resolve.
 */
const ROW =
  "flex items-center gap-3 px-4 rounded-2xl font-medium no-underline w-full text-left " +
  "border-0 cursor-pointer u-press transition-colors duration-[var(--dur-fast)]";

/** The non-active background, for links and buttons alike. */
const ROW_IDLE = "bg-transparent text-ink-muted hover:bg-rule hover:text-ink";

export function SiteHeader() {
  const { session, signOut, initialising } = useAuth();
  const pathname = usePathname();
  const theme = useTheme();
  const isStaff = session && session.role !== "CITIZEN";

  // Restores the theme class after React's dev-only remount clears it. See
  // useThemeSync -- no-op in production.
  useThemeSync();

  /*
   * `md:self-start` on the <header> below is what makes `sticky` work, and its
   * absence is why the sidebar scrolled away with the page.
   *
   * PageShell lays this out as a flex row, and a flex row stretches its
   * children to the row's height by default. So the header was already as tall
   * as the entire page -- and `position: sticky; top: 0` has nothing to do for
   * an element as tall as its own containing block. It never stuck because it
   * never needed to move.
   *
   * `align-self: flex-start` shrinks it to its own height, the inner
   * `h-screen` makes that one viewport, and sticky then pins it while the
   * column beside it scrolls. `overflow-y-auto` sits on the INNER element so a
   * menu taller than the viewport scrolls inside the sidebar rather than
   * pushing the sticky container past screen height, which would break it
   * again for exactly the same reason.
   */

  // Mobile disclosure. Closed by the link's own onClick rather than by an
  // effect watching `pathname` -- an event handler needs no effect, and
  // setting state inside an effect body is what the compiler rules reject.
  const [open, setOpen] = useState(false);
  const close = () => setOpen(false);

  // Sign-in is a dialog now, not /login. See lib/signInDialog for why.
  const { openSignIn } = useSignIn();

  const renderLink = (l: NavLink) => {
    if (l.staffOnly && !isStaff) return null;
    // Exact match, or a child route under it. Plain `startsWith` would light
    // up "/issues" while sitting on "/issues-archive".
    const isActive = pathname === l.href || pathname.startsWith(`${l.href}/`);

    return (
      <Link
        key={l.href}
        href={l.href}
        onClick={close}
        aria-current={isActive ? "page" : undefined}
        className={`${ROW} ${
          isActive ? "bg-brand-dark text-brand-dark-ink font-semibold" : ROW_IDLE
        }`}
        style={{ minHeight: "var(--hit-min)" }}
      >
        <Icon name={l.icon} />
        <span className="flex-1">{l.label}</span>
      </Link>
    );
  };

  return (
    <header className="sticky top-0 z-[500] md:self-start bg-surface-raised border-b md:border-b-0 md:border-r border-rule shrink-0 w-full md:w-[var(--sidebar-width)]">
      <div className="md:h-screen md:overflow-y-auto flex flex-col p-4 md:p-6">
        <div className="flex items-center justify-between">
          <Link
            href="/"
            onClick={close}
            className="text-ink no-underline flex items-center gap-3 px-2"
          >
            <span className="w-9 h-9 rounded-xl bg-brand-primary flex items-center justify-center text-brand-ink flex-shrink-0">
              <svg
                width="20"
                height="20"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2.5"
                strokeLinecap="round"
                strokeLinejoin="round"
                aria-hidden="true"
                focusable="false"
              >
                <path d="M12 2L2 7l10 5 10-5-10-5z" />
                <path d="M2 17l10 5 10-5" />
                <path d="M2 12l10 5 10-5" />
              </svg>
            </span>
            <span className="text-heading font-semibold tracking-tight">CivicTrack</span>
          </Link>

          {/* Below md only: the disclosure for everything under this row. */}
          <button
            type="button"
            onClick={() => setOpen((o) => !o)}
            aria-expanded={open}
            aria-controls="site-nav"
            aria-label={open ? "Close menu" : "Open menu"}
            className="md:hidden flex items-center justify-center rounded-xl border border-rule bg-transparent text-ink cursor-pointer"
            style={{ width: "var(--hit-min)", height: "var(--hit-min)" }}
          >
            <svg
              width="22"
              height="22"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              aria-hidden="true"
              focusable="false"
            >
              {open ? (
                <>
                  <line x1="6" y1="6" x2="18" y2="18" />
                  <line x1="18" y1="6" x2="6" y2="18" />
                </>
              ) : (
                <>
                  <line x1="3" y1="6" x2="21" y2="6" />
                  <line x1="3" y1="12" x2="21" y2="12" />
                  <line x1="3" y1="18" x2="21" y2="18" />
                </>
              )}
            </svg>
          </button>
        </div>

        {/*
          The mobile disclosure animates via `grid-template-rows: 0fr -> 1fr`,
          not by toggling `hidden`.

          Height cannot be transitioned to or from `auto`. The usual
          workarounds either hard-code a max-height, which clips the menu the
          day a link is added, or give up and snap. The grid trick animates to
          whatever the content's real height turns out to be.

          `invisible` while collapsed is not cosmetic: it takes the links out
          of the tab order. A menu that looks closed but is still focusable is
          a keyboard trap, and it is invisible to sighted testing.

          Above `md` every one of these is neutralised -- `md:grid-rows-none`,
          `md:visible`, `md:overflow-visible` -- and the nav is a plain column
          again. One element, one copy of the links.
        */}
        <nav
          id="site-nav"
          aria-label="Main"
          className={`grid md:block flex-1 mt-6 md:mt-8
                      transition-[grid-template-rows] duration-[var(--dur-base)]
                      ease-[var(--ease-standard)] md:transition-none
                      md:grid-rows-none ${open ? "grid-rows-[1fr]" : "grid-rows-[0fr]"}`}
        >
        <div
          className={`overflow-hidden md:overflow-visible flex flex-col md:h-full
                      ${open ? "" : "invisible md:visible"}`}
        >
          <p className="text-meta text-ink-muted uppercase tracking-wider mb-2 px-4">
            Menu
          </p>
          <div className="flex flex-col gap-1">{MENU_LINKS.map(renderLink)}</div>

          <div className="flex flex-col gap-1 mt-8 md:mt-auto md:pt-8">
            {!initialising && session && (
              <>
                <Link
                  href="/me/reports"
                  onClick={close}
                  className={`${ROW} ${ROW_IDLE}`}
                  style={{ minHeight: "var(--hit-min)" }}
                >
                  <Icon name="signIn" />
                  <span className="flex-1 truncate">{session.fullName}</span>
                </Link>
                <button
                  type="button"
                  onClick={() => {
                    close();
                    void signOut();
                  }}
                  className={`${ROW} ${ROW_IDLE}`}
                  style={{ minHeight: "var(--hit-min)" }}
                >
                  <Icon name="signOut" />
                  <span>Sign out</span>
                </button>
              </>
            )}
            {!initialising && !session && (
              <button
                type="button"
                onClick={() => {
                  close();
                  openSignIn();
                }}
                className={`${ROW} ${ROW_IDLE}`}
                style={{ minHeight: "var(--hit-min)" }}
              >
                <Icon name="signIn" />
                <span>Sign in</span>
              </button>
            )}

            <button
              type="button"
              onClick={toggleTheme}
              className={`${ROW} ${ROW_IDLE}`}
              style={{ minHeight: "var(--hit-min)" }}
            >
              <svg
                width="20"
                height="20"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
                aria-hidden="true"
                focusable="false"
              >
                {theme === "dark" ? (
                  <path d="M12 3v1m0 16v1m9-9h-1M4 12H3m15.364 6.364l-.707-.707M6.343 6.343l-.707-.707m12.728 0l-.707.707M6.343 17.657l-.707.707M16 12a4 4 0 11-8 0 4 4 0 018 0z" />
                ) : (
                  <path d="M21 12.79A9 9 0 1111.21 3 7 7 0 0021 12.79z" />
                )}
              </svg>
              <span>{theme === "dark" ? "Light mode" : "Dark mode"}</span>
            </button>
          </div>
        </div>
        </nav>
      </div>
    </header>
  );
}

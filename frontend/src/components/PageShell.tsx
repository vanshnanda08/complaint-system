import { SiteHeader } from "./SiteHeader";

/**
 * Page chrome. Single column, left-aligned (blueprint §1.5).
 *
 * `wide` opts a page out of the prose measure for the surfaces that genuinely
 * need the full width -- tables and maps. Everything else stays inside 68ch,
 * because a line of prose 1100px long is unreadable regardless of how much
 * screen there is.
 */
export function PageShell({
  children,
  wide = false,
}: {
  children: React.ReactNode;
  wide?: boolean;
}) {
  return (
    <>
      <SiteHeader />
      <main className="mx-auto px-4 py-6" style={{ maxWidth: wide ? 1100 : 760 }}>
        {children}
      </main>
    </>
  );
}

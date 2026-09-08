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
    <div className="flex min-h-screen flex-col md:flex-row">
      <SiteHeader />
      {/* `min-w-0` so a wide table or a long unbroken string inside `main`
          cannot force the flex row wider than the viewport -- the default
          `min-width: auto` on a flex item is what turns that into a
          horizontal scrollbar on the whole page. */}
      <main
        className="flex-1 min-w-0 w-full mx-auto px-4 py-6 md:px-8"
        style={{ maxWidth: wide ? 1100 : 760 }}
      >
        {children}
      </main>
    </div>
  );
}

import Link from "next/link";
import { PageShell } from "@/components/PageShell";

/**
 * 404 (blueprint §3.22): states that the page or ticket does not exist, and
 * offers search and the issue index. It does not apologise and it does not
 * redirect -- a redirect would destroy the URL somebody was sent.
 */
export default function NotFound() {
  return (
    <PageShell>
      <h1 className="text-display">That page does not exist</h1>
      <p className="mt-3 text-body" style={{ maxWidth: "var(--measure-prose)" }}>
        The address may have been mistyped, or the ticket number may not be one
        this system issued.
      </p>
      <p className="mt-4 flex flex-wrap gap-4">
        <Link href="/issues" className="text-body underline text-ink">
          Search the issue index
        </Link>
        <Link href="/report" className="text-body underline text-ink">
          Report a problem
        </Link>
      </p>
    </PageShell>
  );
}

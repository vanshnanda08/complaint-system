"use client";

import Link from "next/link";
import { use, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { PageShell } from "@/components/PageShell";
import { Button } from "@/components/Button";
import { EmptyState } from "@/components/EmptyState";
import { ErrorState } from "@/components/ErrorState";
import { LoadingState } from "@/components/LoadingState";
import { Photo } from "@/components/Photo";
import { StatusRule } from "@/components/StatusRule";
import { TextField } from "@/components/TextField";
import { TicketRef } from "@/components/TicketRef";
import { useAuth } from "@/lib/auth";
import { ApiError, isUnknownTicket, request } from "@/lib/api";
import { keys } from "@/lib/queries";
import { absoluteDateTime } from "@/lib/format";
import { statusWord } from "@/lib/status";
import { useSignIn } from "@/lib/signInDialog";
import type { Verdict, VerificationView, VoteResult } from "@/lib/types";

/**
 * Verify a fix (blueprint §3.12): one question, answered in two taps.
 *
 * "Fixed" and "Not fixed" are both `secondary` and both take the same two taps
 * -- choose, then send. Making one of them a single tap would weight it as
 * surely as colouring it would, and the share of fixes citizens accept is the
 * measurement the project rests on.
 *
 * The server decides everything that matters here: whether this caller may
 * vote, what they already said, and what the tally is. The screen renders
 * those answers. In particular `ineligibleReason` is shown verbatim, and a
 * second vote is not prevented by hiding the controls alone -- the endpoint
 * refuses it with 409 and the recorded verdict, and this page then shows that
 * verdict rather than an error (blueprint §3.12).
 */
export default function VerifyPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const { session, initialising, authed } = useAuth();
  const { openSignIn } = useSignIn();
  const qc = useQueryClient();

  const [choice, setChoice] = useState<Verdict | null>(null);
  const [reason, setReason] = useState("");
  const [result, setResult] = useState<VoteResult | null>(null);

  const view = useQuery({
    queryKey: keys.myVerification(session?.userId, id),
    queryFn: () => authed((token) => request<VerificationView>(`/me/verifications/${id}`, { token })),
    enabled: Boolean(session),
    // The tally moves when other reporters vote. Opening the page is the
    // moment somebody wants it current.
    staleTime: 0,
  });

  const vote = useMutation({
    mutationFn: (body: { verdict: Verdict; reason?: string }) =>
      authed((token) =>
        request<VoteResult>(`/issues/${id}/verify`, { method: "POST", token, body }),
      ),
    onSuccess: (r) => setResult(r),
    onSettled: () => {
      // DD-057: a vote can change the issue's status, so it invalidates every
      // view that status appears in -- not only this one. That includes the
      // 409 paths: "already answered" and "voting closed" both mean this
      // screen's copy of the issue was out of date.
      void qc.invalidateQueries({ queryKey: ["me"] });
      void qc.invalidateQueries({ queryKey: ["issue", id] });
      void qc.invalidateQueries({ queryKey: ["issues"] });
      void qc.invalidateQueries({ queryKey: ["bbox"] });
      void qc.invalidateQueries({ queryKey: ["dashboard"] });
    },
  });

  if (initialising) {
    return (
      <PageShell>
        <LoadingState label="Checking your session" />
      </PageShell>
    );
  }

  if (!session) {
    return (
      <PageShell>
        <h1 className="text-display">Is it fixed?</h1>
        <EmptyState
          message="Sign in with the account you reported this from to say whether it is fixed."
          actionLabel="Sign in"
          onAction={openSignIn}
        />
      </PageShell>
    );
  }

  if (view.isPending) {
    return (
      <PageShell wide>
        <LoadingState label="Loading the fix" />
      </PageShell>
    );
  }

  if (view.isError) {
    const err = view.error as ApiError;
    return (
      <PageShell>
        {isUnknownTicket(err) ? (
          <>
            <h1 className="text-display">No such ticket</h1>
            <p className="mt-3 text-body">
              Nothing here has that reference. The link may have been copied incompletely.
            </p>
            <p className="mt-4">
              <Link href="/me/notifications" className="text-body underline text-ink">
                Your notifications
              </Link>
            </p>
          </>
        ) : (
          <ErrorState action="Loading this fix" problem={err?.problem} onRetry={() => void view.refetch()} />
        )}
      </PageShell>
    );
  }

  const v = view.data;
  const i = v.issue;
  const pending = i.status === "PENDING_VERIFICATION";
  // A 409 already-verified carries the recorded verdict, so the screen can
  // show it even before the refetch lands.
  const conflictVerdict =
    vote.error instanceof ApiError && vote.error.status === 409
      ? ((vote.error.problem?.verdict as Verdict | undefined) ?? null)
      : null;
  const recorded = result?.verdict ?? v.myVerdict ?? conflictVerdict;
  const canVote = v.eligible && pending && !recorded;

  const submit = () => {
    if (!choice) return;
    const trimmed = reason.trim();
    vote.mutate(choice === "NOT_FIXED" && trimmed ? { verdict: choice, reason: trimmed } : { verdict: choice });
  };

  return (
    <PageShell wide>
      <div className="flex flex-wrap items-center gap-x-5 gap-y-2">
        <TicketRef publicRef={i.publicRef} issueId={i.id} />
        <StatusRule status={result?.status ?? i.status} />
      </div>

      <h1 className="mt-3 text-display">Is it fixed?</h1>
      <p className="mt-1 text-body text-ink-muted">
        {i.categoryName} · {i.wardName}
        {i.departmentName ? ` · ${i.departmentName}` : ""}
      </p>

      {/* Before and after: side by side from md, stacked below, both full width. */}
      <div className="mt-6 grid grid-cols-1 md:grid-cols-2 gap-4">
        <figure className="m-0">
          <Photo
            src={v.beforePhotoUrl}
            alt="The problem as it was reported"
            width={640}
            height={480}
            className="border border-rule object-cover"
            style={{ borderRadius: "var(--radius)", width: "100%", height: "auto", aspectRatio: "4 / 3" }}
          />
          <figcaption className="mt-2 text-meta text-ink-muted">Before — as it was reported</figcaption>
        </figure>
        <figure className="m-0">
          <Photo
            src={i.resolutionPhotoUrl}
            alt="The photo the department submitted as proof of the fix"
            width={640}
            height={480}
            className="border border-rule object-cover"
            style={{ borderRadius: "var(--radius)", width: "100%", height: "auto", aspectRatio: "4 / 3" }}
          />
          <figcaption className="mt-2 text-meta text-ink-muted">
            After — the department&apos;s photo
            {v.submittedAt ? `, submitted ${absoluteDateTime(v.submittedAt)}` : ""}
          </figcaption>
        </figure>
      </div>

      {i.resolutionNote && (
        <section className="mt-6" style={{ maxWidth: "var(--measure-prose)" }}>
          <h2 className="text-heading">What the department says it did</h2>
          <p className="mt-2 text-body">{i.resolutionNote}</p>
        </section>
      )}

      <section className="mt-8 border-t border-rule pt-6" style={{ maxWidth: "var(--measure-prose)" }}>
        {!v.eligible && <p className="text-body">{v.ineligibleReason}</p>}

        {v.eligible && recorded && (
          <Recorded verdict={recorded} reason={result ? reason.trim() || null : v.myReason} result={result} />
        )}

        {v.eligible && !recorded && !pending && (
          <p className="text-body">
            This fix is no longer waiting for an answer. The issue is now{" "}
            {statusWord(i.status).toLowerCase()}.
          </p>
        )}

        {canVote && (
          <>
            <h2 className="text-heading">You reported this. Is the problem gone?</h2>
            <div className="mt-4 grid grid-cols-2 gap-3" role="group" aria-label="Your answer">
              <Button
                variant="secondary"
                aria-pressed={choice === "FIXED"}
                onClick={() => setChoice("FIXED")}
                className={choice === "FIXED" ? SELECTED : ""}
              >
                Fixed
              </Button>
              <Button
                variant="secondary"
                aria-pressed={choice === "NOT_FIXED"}
                onClick={() => setChoice("NOT_FIXED")}
                className={choice === "NOT_FIXED" ? SELECTED : ""}
              >
                Not fixed
              </Button>
            </div>

            {choice === "NOT_FIXED" && (
              <div className="mt-4">
                <TextField
                  label="What is still wrong? (optional)"
                  hint="One line. The department sees this."
                  value={reason}
                  maxLength={280}
                  onChange={(e) => setReason(e.target.value)}
                />
              </div>
            )}

            {choice && (
              <div className="mt-4">
                <Button onClick={submit} disabled={vote.isPending}>
                  {vote.isPending ? "Sending" : choice === "FIXED" ? "Send: fixed" : "Send: not fixed"}
                </Button>
              </div>
            )}

            {vote.isError && !conflictVerdict && (
              <ErrorState action="Sending your answer" problem={(vote.error as ApiError)?.problem} />
            )}
          </>
        )}

        {pending && <Tally view={v} result={result} />}
      </section>
    </PageShell>
  );
}

/**
 * The chosen answer. A ring rather than a style prop or a border utility:
 * Button sets its own inline style (the 48px hit minimum), which a style prop
 * would replace, and its own border, whose width a second border utility would
 * fight in stylesheet order. Same ring on both answers, so neither is favoured.
 */
const SELECTED = "font-semibold ring-2 ring-ink ring-offset-2 ring-offset-surface";

function verdictWord(verdict: Verdict) {
  return verdict === "FIXED" ? "fixed" : "not fixed";
}

/** What this citizen said, and -- straight after voting -- what it did. */
function Recorded({
  verdict,
  reason,
  result,
}: {
  verdict: Verdict;
  reason: string | null;
  result: VoteResult | null;
}) {
  return (
    <>
      <p className="text-body">
        You said this is <strong>{verdictWord(verdict)}</strong>.
        {reason ? ` Your reason: “${reason}”.` : ""}
      </p>
      {result?.outcome === "REOPENED" && (
        <p className="mt-2 text-body">
          Your answer reopened the issue. The department has been told the fix did not hold.
        </p>
      )}
      {result?.outcome === "RESOLVED" && (
        <p className="mt-2 text-body">
          Your answer completed the confirmation. The issue is now resolved.
        </p>
      )}
      {result?.outcome === "STILL_OPEN" && (
        <p className="mt-2 text-body">
          Recorded. The issue stays open for the others who reported it.
        </p>
      )}
    </>
  );
}

/** The tally in plain words, and when silence starts to count (blueprint §3.12). */
function Tally({ view, result }: { view: VerificationView; result: VoteResult | null }) {
  // The vote response is newer than the view until the refetch lands.
  const confirmations = result?.confirmations ?? view.confirmations;
  const rejections = result?.rejections ?? view.rejections;
  const required = result?.required ?? view.required;
  const people = (n: number) => (n === 1 ? "1 person" : `${n} people`);

  return (
    <div className="mt-6 text-dense text-ink-muted">
      <p>
        So far {people(confirmations)} {confirmations === 1 ? "has" : "have"} said fixed and{" "}
        {people(rejections)} not fixed. {required} confirmation{required === 1 ? "" : "s"}{" "}
        {required === 1 ? "is" : "are"} needed to close it; if as many say not fixed as say fixed,
        it reopens.
      </p>
      {view.silenceDeadline && (
        <p className="mt-2">
          If nobody says it is not fixed by {absoluteDateTime(view.silenceDeadline)}, it counts as fixed.
        </p>
      )}
    </div>
  );
}

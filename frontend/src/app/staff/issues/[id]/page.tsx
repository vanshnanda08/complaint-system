"use client";

import Link from "next/link";
import { use, useState } from "react";
import { useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { PageShell } from "@/components/PageShell";
import { StatusRule } from "@/components/StatusRule";
import { TicketRef } from "@/components/TicketRef";
import { PriorityBadge } from "@/components/PriorityBadge";
import { DeadlineCountdown } from "@/components/DeadlineCountdown";
import { Button } from "@/components/Button";
import { PhotoUploader } from "@/components/PhotoUploader";
import { keys } from "@/lib/queries";
import { UploadProgress } from "@/components/UploadProgress";
import { ErrorState } from "@/components/ErrorState";
import { LoadingState } from "@/components/LoadingState";
import { EmptyState } from "@/components/EmptyState";
import { useAuth } from "@/lib/auth";
import { useCategories, useWards } from "@/lib/queries";
import { ApiError, request } from "@/lib/api";
import { isOverdue, statusWord } from "@/lib/status";
import { nextAction, waitingOn, type ActorContext } from "@/lib/transitions";
import { proofSchema, MIN_PROOF_NOTE, type ProofValues } from "@/lib/schemas";
import { ageLabel, reporters } from "@/lib/format";
import { uploadPhoto } from "@/lib/uploadPhoto";
import type { CompressedImage } from "@/lib/compressImage";
import type { StaffIssue } from "@/lib/types";
import { useSignIn } from "@/lib/signInDialog";

/**
 * Work view (blueprint §3.15).
 *
 * THE SINGLE ACTION AVAILABLE IN THE CURRENT STATE. Actions the state machine
 * does not permit are ABSENT, not disabled. A disabled button advertises a
 * capability the person does not have and invites them to hunt for the
 * condition that unlocks it; an absent one says the move is not part of this
 * state, which is the truth.
 *
 * The proof control is labelled "Submit for citizen verification", never
 * "Resolve", because staff cannot resolve -- there is no transition from any
 * staff-reachable state to RESOLVED, and the endpoint is named
 * `submit-for-verification` for the same reason. The label is the transition
 * table made visible to the person governed by it (blueprint §9).
 *
 * A rejected transition renders the server's ProblemDetail VERBATIM. The state
 * machine is the authority; this screen does not paraphrase it.
 */

export default function StaffWorkViewPage({ params }: { params: Promise<{ id: string }> }) {
  const { openSignIn } = useSignIn();
  const { id } = use(params);
  const { session, initialising, authed } = useAuth();
  const qc = useQueryClient();

  const [proof, setProof] = useState<CompressedImage | null>(null);
  const [uploadFraction, setUploadFraction] = useState<number | null>(null);
  const [problem, setProblem] = useState<ApiError | null>(null);

  // IssueDto carries codes, not display names. The categories and wards
  // queries are cached at infinity (blueprint §6), so resolving names from them
  // costs nothing and avoids widening the staff DTO with joins.
  const categories = useCategories();
  const wards = useWards();

  const issue = useQuery({
    queryKey: ["staff", "issue", id],
    queryFn: () => authed((token) => request<StaffIssue>(`/issues/${id}`, { token })),
    enabled: Boolean(session),
    staleTime: 0,
  });

  // The proof form. Its two rules -- a photo, and a note of at least twenty
  // characters -- are the server's own (`ResolveRequest`), stated once in
  // lib/schemas.ts rather than re-expressed here as inline conditions.
  const proofForm = useForm<ProofValues>({
    resolver: zodResolver(proofSchema),
    mode: "onSubmit",
    reValidateMode: "onChange",
    defaultValues: { note: "", hasPhoto: false as true },
  });
  // useWatch, not watch(): watch() returns a function the React Compiler
  // cannot memoise, and it re-renders on every keystroke in the form.
  const noteValue = useWatch({ control: proofForm.control, name: "note" });

  const act = useMutation({
    mutationFn: async ({ verb, note }: { verb: string; note: string }) => {
      setProblem(null);

      let body: Record<string, unknown> = { note: note || null };

      if (verb === "submit-for-verification") {
        let proofPhotoUrl: string | null = null;
        if (proof) {
          setUploadFraction(0);
          proofPhotoUrl = (await uploadPhoto(proof.blob, setUploadFraction)).url;
          setUploadFraction(null);
        }
        body = { proofPhotoUrl, note };
      }

      return authed((token) =>
        request<StaffIssue>(`/issues/${id}/${verb}`, { method: "POST", token, body }),
      );
    },
    onSuccess: () => {
      proofForm.reset();
      setProof(null);
      /*
       * Everything a transition changes, not just what is on screen.
       *
       * A status change is not local to this page. It moves the issue between
       * the public list's status filters, recolours its marker on the map, and
       * -- when the transition pauses or restarts the SLA clock -- moves the
       * dashboard's overdue count. Invalidating only `staff` and this issue
       * left those three showing the previous number until their own 30s stale
       * time expired, which reads as the application ignoring the action that
       * was just taken.
       *
       * These are key PREFIXES: `keys.issues(filters)` is `["issues", filters]`
       * and `keys.bbox(params)` is `["bbox", params]`, so the bare prefix
       * matches every filter and viewport variant rather than only the one
       * currently mounted.
       */
      void qc.invalidateQueries({ queryKey: ["staff"] });
      void qc.invalidateQueries({ queryKey: ["issue", id] });
      void qc.invalidateQueries({ queryKey: ["issues"] });
      void qc.invalidateQueries({ queryKey: ["bbox"] });
      void qc.invalidateQueries({ queryKey: keys.summary() });
    },
    onError: (e) => setProblem(e instanceof ApiError ? e : null),
  });

  if (initialising || (issue.isPending && session)) {
    return (
      <PageShell wide>
        <LoadingState label="Loading the issue" />
      </PageShell>
    );
  }

  if (!session || session.role === "CITIZEN") {
    return (
      <PageShell>
        <EmptyState
          message="This is the municipal work view. Sign in with a staff account to see it."
          actionLabel="Sign in"
          onAction={openSignIn}
        />
      </PageShell>
    );
  }

  if (issue.isError) {
    const err = issue.error as ApiError;
    return (
      <PageShell wide>
        <ErrorState
          action="Loading this issue"
          problem={
            err?.status === 403
              ? { detail: "This issue belongs to another department or ward." }
              : err?.problem
          }
          onRetry={() => void issue.refetch()}
        />
      </PageShell>
    );
  }

  const i = issue.data!;
  const categoryName =
    categories.data?.find((c) => c.code === i.categoryCode)?.displayName ?? i.categoryCode;
  const wardName = wards.data?.find((w) => w.id === i.wardId)?.name ?? "";
  const overdue = isOverdue(i.status, i.effectiveDeadline);
  const actorContext: ActorContext = {
    role: session.role,
    userId: session.userId,
    assignedTo: i.assignedTo,
  };
  const action = nextAction(i.status, actorContext);
  const needsProof = action?.verb === "submit-for-verification";

  return (
    <PageShell wide>
      <p className="text-meta">
        <Link href="/staff/queue" className="text-ink underline">
          ← Back to the queue
        </Link>
      </p>

      <div className="mt-2 flex flex-wrap items-center gap-x-5 gap-y-2">
        <TicketRef publicRef={i.publicRef} />
        <StatusRule status={i.status} overdue={overdue} audience="staff" />
        <PriorityBadge priority={i.priority} score={i.priorityScore} />
        <span className="ml-auto">
          <DeadlineCountdown
            status={i.status}
            effectiveDeadline={i.effectiveDeadline}
            pausedSeconds={i.pausedSeconds}
            resolvedAt={i.resolvedAt}
          />
        </span>
      </div>

      <h1 className="mt-3 text-display">{categoryName}</h1>
      <p className="mt-1 text-dense text-ink-muted">
        {wardName ? `${wardName} · ` : ""}
        {reporters(i.distinctReporterCount)} · {ageLabel(i.firstReportedAt)}
        {i.escalationLevel > 0 ? ` · escalated to level ${i.escalationLevel}` : ""}
      </p>

      <p className="mt-2">
        <Link href={`/issues/${i.id}`} className="text-dense underline text-ink">
          Public view
        </Link>
        {" · "}
        <Link href={`/issues/${i.id}/cluster`} className="text-dense underline text-ink">
          How these reports were grouped
        </Link>
      </p>

      <section className="mt-8 border-t border-rule pt-5">
        {action ? (
          <>
            <h2 className="text-heading">{action.label}</h2>

            {needsProof ? (
              <>
                <p className="mt-1 text-dense text-ink-muted" style={{ maxWidth: "var(--measure-prose)" }}>
                  This does not resolve the issue. It sends the fix to the people
                  who reported it, and they decide. The clock pauses while they do.
                </p>

                <div className="mt-4" style={{ maxWidth: 520 }}>
                  <PhotoUploader
                    onChange={(img) => {
                      setProof(img);
                      proofForm.setValue("hasPhoto", (img != null) as true, {
                        shouldValidate: proofForm.formState.isSubmitted,
                      });
                    }}
                    error={proofForm.formState.errors.hasPhoto?.message}
                  />
                </div>

                <label className="mt-4 flex flex-col gap-1" style={{ maxWidth: "var(--measure-prose)" }}>
                  <span className="text-meta">What was done</span>
                  <span className="text-meta text-ink-muted">
                    At least {MIN_PROOF_NOTE} characters. This is shown to everyone who reported it.
                  </span>
                  <textarea
                    {...proofForm.register("note")}
                    rows={3}
                    className="bg-surface-raised border border-rule px-3 py-2 text-body"
                    style={{ borderRadius: "var(--radius)" }}
                  />
                  <span className="text-meta text-ink-muted">
                    {(noteValue ?? "").trim().length} of {MIN_PROOF_NOTE} characters
                  </span>
                  {proofForm.formState.errors.note && (
                    <span className="text-meta" style={{ color: "var(--st-breached)" }} role="alert">
                      {proofForm.formState.errors.note.message}
                    </span>
                  )}
                </label>
              </>
            ) : (
              <label className="mt-3 flex flex-col gap-1" style={{ maxWidth: "var(--measure-prose)" }}>
                <span className="text-meta">Note (optional)</span>
                <textarea
                  {...proofForm.register("note")}
                  rows={2}
                  className="bg-surface-raised border border-rule px-3 py-2 text-body"
                  style={{ borderRadius: "var(--radius)" }}
                />
              </label>
            )}

            {uploadFraction !== null && (
              <div className="mt-4" style={{ maxWidth: 520 }}>
                <UploadProgress fraction={uploadFraction} />
              </div>
            )}

            {problem && (
              <div className="mt-4">
                {/* Verbatim. The state machine's own sentence. */}
                <ErrorState action={action.label} problem={problem.problem} />
              </div>
            )}

            <div className="mt-5">
              <Button
                onClick={
                  needsProof
                    ? proofForm.handleSubmit((v) =>
                        act.mutate({ verb: action.verb, note: v.note }),
                      )
                    : () => act.mutate({ verb: action.verb, note: noteValue ?? "" })
                }
                disabled={act.isPending}
              >
                {act.isPending ? "Sending…" : action.label}
              </Button>
            </div>
          </>
        ) : (
          <>
            <h2 className="text-heading">{statusWord(i.status, "staff")}</h2>
            <p className="mt-2 text-body" style={{ maxWidth: "var(--measure-prose)" }}>
              {waitingOn(i.status, actorContext)}
            </p>
          </>
        )}
      </section>
    </PageShell>
  );
}

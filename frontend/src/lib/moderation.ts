import type { QueryClient } from "@tanstack/react-query";

/**
 * Why the clustering engine asked a person to look, in words a supervisor can
 * act on (blueprint 3.17: "each labelled with its cause"). The keys are the
 * review_reason values ClusteringService writes.
 */
export const REVIEW_CAUSE: Record<string, { title: string; explain: string }> = {
  LOW_CONF_MERGE: {
    title: "Merged, but only just",
    explain:
      "A report landed just outside the confident merge radius and was merged anyway. Check it is the same problem.",
  },
  LOW_CONF_SPLIT: {
    title: "Kept apart to be safe",
    explain:
      "A report landed just outside the merge radius in a safety-critical category, so it was kept as its own issue rather than risk hiding a second hazard. Merge it if it is a duplicate.",
  },
  EXTENT_CAP: {
    title: "Refused by the extent cap",
    explain:
      "Merging this report would have stretched the cluster past its cap, so it started a new issue. A long defect along a road can do this. Merge if it is one problem.",
  },
};

export function reviewCause(reason: string | null) {
  return (reason && REVIEW_CAUSE[reason]) || { title: "Flagged for review", explain: "The clustering engine asked for a person to check this grouping." };
}

/**
 * DD-057, for moderation: a split or merge changes report counts, centroids,
 * statuses and the overdue figure, so it invalidates every view those appear
 * in -- not only the screen the supervisor is on.
 */
export function invalidateAfterModeration(qc: QueryClient) {
  for (const key of [["supervisor"], ["issue"], ["issues"], ["bbox"], ["dashboard"], ["staff"]]) {
    void qc.invalidateQueries({
      queryKey: key,
      // Not the previews. A preview describes a selection that the action
      // just consumed -- refetching a merge preview for an issue that is now
      // merged away is a 409, and a split preview for reports that have moved
      // is another. The browser walk caught both as console errors.
      predicate: (q) => !String(q.queryKey[1] ?? "").endsWith("-preview"),
    });
  }
}

package com.civictrack.report;

/**
 * Why a report landed where it landed. Stored on every report row, so the
 * clustering audit trail can be queried directly and the extent-cap ablation
 * (DD-001) can be run over recorded decisions rather than by re-running the
 * pipeline.
 */
public enum ClusterDecision {

    /** No candidate within range, or the nearest was beyond 1.5 x R_eff. */
    NEW_ISSUE,

    /** Distance within R_eff. The ordinary merge. */
    MERGED,

    /** In the low-confidence band, category policy MERGE_FLAG (DD-002). */
    MERGED_LOW_CONF,

    /** In the low-confidence band, category policy SPLIT_FLAG (DD-002). */
    SPLIT_LOW_CONF,

    /**
     * Would have merged on distance, but the projected cluster extent exceeded
     * the category cap, so a separate flagged issue was created (DD-001).
     */
    SPLIT_EXTENT_CAPPED,

    /** A supervisor moved this report by hand via the split/merge tool. */
    MANUAL
}

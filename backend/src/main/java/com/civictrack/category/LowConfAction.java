package com.civictrack.category;

/**
 * What the low-confidence band does for a given category (DD-002).
 *
 * <p>The band is the distance range between R_eff and 1.5 x R_eff, where a
 * hard threshold would produce arbitrary decisions. Rather than guess, the
 * system acts according to which error is cheaper <em>for this defect type</em>
 * and flags the outcome for review either way.
 */
public enum LowConfAction {

    /**
     * Merge optimistically and flag. Correct where a wrong merge costs one
     * click to split and a wrong split leaves two tickets nobody notices.
     */
    MERGE_FLAG,

    /**
     * Create a separate flagged issue. Correct where concealing a duplicate is
     * itself the hazard -- a second open manhole hidden behind an incremented
     * counter stays open while the first is fixed and the ticket closes.
     */
    SPLIT_FLAG
}

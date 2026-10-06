package com.civictrack.notification;

/** Why somebody was told something. Stored, so a client can choose a glyph. */
public enum NotificationType {

    /** A fix was claimed on an issue you reported. You decide whether it holds. */
    VERIFY_REQUESTED,

    /** An issue you reported was resolved -- by vote, or by the timeout. */
    RESOLVED,

    /** Citizens rejected a fix, or the problem recurred. Sent to reporters and to the assignee. */
    REOPENED,

    /** A supervisor rejected an issue you reported, with a reason. */
    REJECTED
}

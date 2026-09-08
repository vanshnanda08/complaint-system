package com.civictrack.issue;

/**
 * Which slice of the department's work a staff member is looking at.
 *
 * <p>Blueprint 3.14 specifies two tabs -- what is assigned to me, and what the
 * department has not picked up. {@link #ALL} is the default because it is what
 * the queue meant before the tabs existed, and changing the meaning of a
 * request that omits the parameter would silently narrow every existing
 * caller.
 *
 * <p>Parsed leniently: an unrecognised value falls back to {@link #ALL} rather
 * than 400. The tab is a view preference arriving from a URL that a person may
 * have edited or a link that may have aged, and the useful response to "tab=
 * mien" is the queue, not an error page.
 */
public enum QueueTab {

    ALL,
    MINE,
    UNASSIGNED;

    public static QueueTab parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ALL;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return ALL;
        }
    }
}

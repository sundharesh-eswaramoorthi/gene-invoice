package com.geneinvoice.automation;

/**
 * What happened to a record, as far as a rule is concerned (A1).
 *
 * <p>There is no DELETED constant. A hard delete leaves nothing for a rule to read, condition on
 * or act against, so a deletion is dropped at the feed rather than published as a fact nobody can
 * use (A1).
 */
public enum Change {

    CREATED,
    UPDATED;

    /**
     * The one a coalesced pair keeps: CREATED wins.
     *
     * <p>A single transaction that creates a record and then updates it — which is every
     * invoice create that also spends customer credit — is ONE creation as far as a rule author
     * is concerned, and a rule armed on "created" must see it (A1).
     */
    public static Change strongest(Change a, Change b) {
        if (a == null) return b;
        if (b == null) return a;
        return a == CREATED || b == CREATED ? CREATED : UPDATED;
    }
}

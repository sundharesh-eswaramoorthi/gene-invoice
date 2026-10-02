package com.geneinvoice.history;

/**
 * A seam for tests to make capture fail where a real mapping error would, or to hold a writer at a point a
 * concurrent writer could otherwise slip in: the writer calls it for every record it is about to snapshot and
 * every link owner it versions. The production bean ({@link NoHistoryFaults}) does nothing.
 */
public interface HistoryFaults {

    /** Called before the record {@code (code, id)} is snapshotted; throwing stands in for a mapping error. */
    void beforeSnapshot(String code, Long id);

    /**
     * Called for every owner of the link kind {@code code} once its current targets are read and before its open
     * rows are, while the writer holds the owner's lock; a test holds a writer here to show another relink waits.
     */
    default void betweenLinkReads(String code, Long ownerId) {
        // nothing to inject
    }
}

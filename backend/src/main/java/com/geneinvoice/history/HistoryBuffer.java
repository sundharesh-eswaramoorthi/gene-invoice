package com.geneinvoice.history;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What changed in ONE transaction, as keys and nothing else (B3).
 *
 * <p>KEYS ONLY, AND THAT IS THE WHOLE POINT. The Hibernate listener fires once per statement, so
 * a payment that touches its invoice six times inside one transaction fires six times; keying on
 * {@code (entityType, id)} collapses those into one entry and the snapshot is taken LATER, from
 * the final flushed state. Serialising at event time instead would write six versions of one
 * invoice for one business change, five of which never existed as far as anybody outside the
 * transaction is concerned.
 *
 * <p>ONE BUFFER PER TRANSACTION, held on {@link HistorySynchronization} rather than on a bean or
 * a bound resource, for exactly the reason {@code ChangeFeed.publication()} gives: a
 * {@code BulkExecutor} row running under PROPAGATION_REQUIRES_NEW gets its own synchronization
 * list, therefore its own buffer, therefore its own drain — so that row's mirror rows commit with
 * that row and with nobody else's (A5, B3 INTEGRATION).
 */
public final class HistoryBuffer {

    /**
     * What about a record changed. A promise's row and the join tables it owns are two different
     * writes against two different mirrors, and a collection event tells us about the second
     * without saying anything about the first: writing a new promise version because somebody
     * added an invoice to it would split the promise's interval chain for a change that is not
     * on the promise row at all (B3).
     */
    public enum Part {
        /** The record's own columns: a POST_INSERT, POST_UPDATE or POST_DELETE on the entity. */
        ROW,
        /** The {@code @ManyToMany} join tables it owns: the three collection events. */
        LINKS
    }

    /**
     * One record. {@code entityType} is the LIVE entity class — never a Hibernate proxy subclass,
     * because the listener reads it off the persister — and is what {@link HistoryRegistry#forType}
     * is looked up by (B3).
     */
    public record Key(Class<?> entityType, Long id) {
    }

    // Insertion-ordered so a drain is reproducible when two keys sort equal, which they cannot,
    // and so a debugger shows the order the events actually arrived in (B3).
    private final Map<Key, EnumSet<Part>> touched = new LinkedHashMap<>();

    public void touch(Class<?> entityType, Long id, Part part) {
        if (entityType == null || id == null || part == null) return;
        touched.computeIfAbsent(new Key(entityType, id), k -> EnumSet.noneOf(Part.class)).add(part);
    }

    /**
     * DETERMINISTIC ORDER, and it is not cosmetic: two transactions writing mirror rows for the
     * same pair of records must queue the same way, or each can hold the row the other is about
     * to need and the bounded retry in {@link HistoryWriter} turns into a livelock. Sorted by
     * class name then id, which is total over the keys this buffer can hold (B3).
     */
    public List<Key> keysSorted() {
        return touched.keySet().stream()
                .sorted(Comparator.comparing((Key k) -> k.entityType().getName())
                        .thenComparing(Key::id))
                .toList();
    }

    public Set<Part> parts(Key key) {
        EnumSet<Part> parts = touched.get(key);
        return parts == null ? Set.of() : Set.copyOf(parts);
    }

    public boolean isEmpty() {
        return touched.isEmpty();
    }

    public int size() {
        return touched.size();
    }

    public void clear() {
        touched.clear();
    }
}

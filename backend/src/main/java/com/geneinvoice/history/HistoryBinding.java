package com.geneinvoice.history;

import java.util.List;
import java.util.Set;

/**
 * Everything the writer, the reconciler and the startup check need to know about ONE mirror, in
 * one record, so that eleven mirrors are eleven lines of data rather than eleven hand-written
 * code paths (B3).
 *
 * @param entityClass       the LIVE entity this mirrors. On the two link mirrors, which mirror a
 *                          {@code @ManyToMany} join table that has no entity of its own, it is the
 *                          OWNER whose collection events produce the rows — {@code PaymentPromise}
 *                          — which is why they are reached through
 *                          {@link HistoryRegistry#linksOf} and never through
 *                          {@link HistoryRegistry#forType}.
 * @param mirrorClass       the mirror @Entity
 * @param liveTable         the live table name, for the drift query
 * @param mirrorTable       the mirror table name
 * @param businessIdColumn  the column on the mirror holding the record's own id, mapped under the
 *                          JPA attribute name {@code id}
 * @param mirrorColumns     the BUSINESS columns of the mirror, in insert order, and nothing else:
 *                          not {@code history_id}, not {@code businessIdColumn}, and not the five
 *                          interval columns the writer fills itself ({@code valid_from},
 *                          {@code valid_to}, {@code deleted}, {@code drifted},
 *                          {@code changed_by_user_id}). The insert is therefore
 *                          {@code (businessIdColumn, valid_from, valid_to, deleted, drifted,
 *                          changed_by_user_id, mirrorColumns...)} for every binding.
 * @param projector         fills one {@link HistoryJdbc.Row} from the managed live entity
 * @param driftSql          the reconciler's keyset-chunked diff, taking exactly three parameters
 *                          in this order: the OPEN sentinel, the id to read after, and the chunk
 *                          size. It selects the business id (and, for a link mirror, the second
 *                          id) of every live row whose open mirror row is missing or disagrees.
 * @param notMirrored       persisted attributes of the live entity that deliberately have NO
 *                          mirror column. The startup check at W18 reads this and nothing else,
 *                          so an attribute added next quarter is a boot failure rather than a
 *                          column that silently stops being temporal.
 */
public record HistoryBinding(Class<?> entityClass,
                             Class<? extends HistoryRow> mirrorClass,
                             String liveTable,
                             String mirrorTable,
                             String businessIdColumn,
                             List<String> mirrorColumns,
                             Projector projector,
                             String driftSql,
                             Set<String> notMirrored) {

    /**
     * THE STANDING REASON, written once. A counter is not a business fact: it says how many times
     * a row has been written, which is meaningless outside the row it guards and is answered
     * better by the mirror's own interval chain anyway (B3, B2).
     */
    public static final String VERSION_NOT_A_FACT =
            "an optimistic-lock counter is not a business fact and is meaningless outside its own row";

    /** The four entities S5 and B2 gave a @Version to, all four of which must declare it here. */
    public static final Set<String> VERSION = Set.of("version");

    public HistoryBinding {
        mirrorColumns = List.copyOf(mirrorColumns);
        notMirrored = Set.copyOf(notMirrored);
    }

    /**
     * Hand-written, per entity, and deliberately NOT reflective: the startup check has something
     * to cross-check the metamodel against, and a renamed field is a COMPILE error here rather
     * than a column that quietly starts writing null (B3).
     *
     * <p>{@code live} is the managed, flushed entity — so a lazy association can still be walked
     * for a denormalised label — except on a link mirror, where it is a {@link Link} (B3).
     */
    @FunctionalInterface
    public interface Projector {
        void project(Object live, HistoryJdbc.Row row);
    }

    /**
     * One row of a {@code @ManyToMany} join table. The two promise link tables have no entity, so
     * a collection event yields the owning promise's id and the other side's id and nothing else
     * to read (B3).
     */
    public record Link(Long ownerId, Long otherId) {
    }
}

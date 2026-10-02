package com.geneinvoice.history;

import org.hibernate.engine.spi.SharedSessionContractImplementor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** How one kind of record becomes versions, and when two versions say the same thing. */
public interface TypeHistory<L, V extends VersionRow> {

    String code();

    Class<L> liveType();

    Class<V> versionType();

    /** Types are written in ascending rank, so every writer takes their rows in one order. */
    int rank();

    /**
     * Versions of managed live records (flushed), keyed by record id. Only the business columns are filled; the
     * validity window and provenance are the writer's. May run one batched child query on {@code s} (an
     * invoice's items); pure mapping otherwise.
     */
    Map<Long, V> snapshotAll(Collection<L> live, SharedSessionContractImplementor s);

    /**
     * Business equality over every stored column except id, versionId, validFrom, validTo, txId, origin,
     * originRefId, actorUserId and the type's own exclusions ({@code updatedAt}, {@code brokenNotifiedAt}).
     * Amounts compare by value, not scale; instants at the microsecond the database keeps.
     */
    boolean sameState(V open, V next);

    /** Records as they were, in input order, rebuilt from versions. */
    List<L> rehydrate(List<V> versions, Rehydration ctx);

    static boolean sameAmount(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    /** Both columns are {@code timestamp(6)}: a value is compared as the database will round and keep it. */
    static boolean sameInstant(Instant a, Instant b) {
        return a == null ? b == null : b != null && micros(a).equals(micros(b));
    }

    /** Rounded half up to the microsecond, as H2 and Postgres store it. */
    static Instant micros(Instant t) {
        long micros = (t.getNano() + 500L) / 1000L;
        return t.truncatedTo(ChronoUnit.SECONDS).plus(micros, ChronoUnit.MICROS);
    }
}

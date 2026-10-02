package com.geneinvoice.history;

import com.geneinvoice.common.scope.AsOf;
import com.geneinvoice.common.scope.AsOfException;
import org.hibernate.Hibernate;
import org.hibernate.event.spi.PostLoadEvent;
import org.hibernate.event.spi.PostLoadEventListener;

import java.util.function.Supplier;

/**
 * Refuses to load a live versioned record while a point is bound (§5.7.16): a read path that was never taught to
 * answer as of a date fails with {@code AS_OF_UNSUPPORTED} instead of showing today's data as history. The few
 * reads that knowingly want the live record (current relationships, a gap in history) go through {@link #allow}.
 */
public class LiveReadGuard implements PostLoadEventListener {

    private static final ThreadLocal<Integer> ALLOWED = ThreadLocal.withInitial(() -> 0);

    @Override
    public void onPostLoad(PostLoadEvent event) {
        if (AsOf.active() && ALLOWED.get() == 0 && HistoryModel.isVersioned(Hibernate.getClass(event.getEntity()))) {
            throw AsOfException.unsupported();
        }
    }

    /** Runs {@code body} with live versioned records readable even while a point is bound. */
    public static <T> T allow(Supplier<T> body) {
        ALLOWED.set(ALLOWED.get() + 1);
        try {
            return body.get();
        } finally {
            int left = ALLOWED.get() - 1;
            if (left == 0) ALLOWED.remove();
            else ALLOWED.set(left);
        }
    }
}

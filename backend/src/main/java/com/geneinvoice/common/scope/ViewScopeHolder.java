package com.geneinvoice.common.scope;

import java.util.function.Supplier;

/**
 * The {@link ViewScope} bound to this thread. Filled by {@link ViewScopeInterceptor} for a request, and by
 * run-as code for background work; read by everything that answers "which regions" or "as of when".
 */
public final class ViewScopeHolder {

    private static final ThreadLocal<ViewScope> CURRENT = new ThreadLocal<>();

    private ViewScopeHolder() {}

    public static ViewScope current() {
        ViewScope s = CURRENT.get();
        return s != null ? s : ViewScope.LIVE_ALL;
    }

    public static <T> T with(ViewScope s, Supplier<T> body) {
        ViewScope previous = CURRENT.get();
        CURRENT.set(s);
        try {
            return body.get();
        } finally {
            restore(previous);
        }
    }

    public static void with(ViewScope s, Runnable body) {
        with(s, () -> {
            body.run();
            return null;
        });
    }

    public static void set(ViewScope s) {
        restore(s);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Defence in depth for write paths: nothing is changed while a past date is being viewed. */
    public static void requireLive() {
        ViewScope s = current();
        if (s.historical()) throw AsOfException.readOnly(s.asOf().date());
    }

    private static void restore(ViewScope s) {
        if (s == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(s);
        }
    }
}

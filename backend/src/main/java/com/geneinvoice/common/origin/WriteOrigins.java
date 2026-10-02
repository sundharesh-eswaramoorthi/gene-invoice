package com.geneinvoice.common.origin;

import com.geneinvoice.auth.CurrentUser;

import java.util.function.Supplier;

/**
 * The {@link WriteOrigin} bound to this thread. Code that runs a transaction under another identity binds
 * it around the transaction, so it is still bound when commit-time writers read it.
 */
public final class WriteOrigins {

    private static final ThreadLocal<WriteOrigin> CURRENT = new ThreadLocal<>();

    private WriteOrigins() {}

    /** The bound origin; otherwise USER when someone is signed in, else SYSTEM. */
    public static WriteOrigin current(CurrentUser cu) {
        WriteOrigin bound = CURRENT.get();
        if (bound != null) return bound;
        return cu != null && cu.idOrNull() != null ? WriteOrigin.user() : WriteOrigin.system();
    }

    /** Who the write is attributed to: the bound origin's actor when set, otherwise the signed-in user. */
    public static Long actor(CurrentUser cu) {
        WriteOrigin bound = CURRENT.get();
        if (bound != null && bound.actorUserId() != null) return bound.actorUserId();
        return cu == null ? null : cu.idOrNull();
    }

    public static <T> T callWith(WriteOrigin o, Supplier<T> body) {
        WriteOrigin previous = CURRENT.get();
        CURRENT.set(o);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static void run(WriteOrigin o, Runnable body) {
        callWith(o, () -> {
            body.run();
            return null;
        });
    }

    public static void clear() {
        CURRENT.remove();
    }
}

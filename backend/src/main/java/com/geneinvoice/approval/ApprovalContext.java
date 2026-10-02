package com.geneinvoice.approval;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The approval being applied on this thread, if any (§5.11.2). While it is bound every gate only verifies: the
 * approved change must not have grown. Who the writes are attributed to travels in the WriteOrigin (D4).
 */
public final class ApprovalContext {

    public record Applying(long pendingChangeId, long checkerUserId, ApprovalOperation op, String outstandingKey,
                           Long customerId, BigDecimal approvedAmount, Instant requestedAt) {}

    private static final ThreadLocal<Applying> CURRENT = new ThreadLocal<>();

    private ApprovalContext() {}

    public static Optional<Applying> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static <T> T applying(Applying a, Supplier<T> body) {
        Applying previous = CURRENT.get();
        CURRENT.set(a);
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
}

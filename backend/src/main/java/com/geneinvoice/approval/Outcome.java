package com.geneinvoice.approval;

import java.util.Optional;
import java.util.function.Function;

/**
 * What a gated service method did (R7: a hold is returned, never thrown): it applied the change, or it held it
 * as a pending change and changed nothing else.
 */
public sealed interface Outcome<T> permits Outcome.Applied, Outcome.Held {

    record Applied<T>(T value) implements Outcome<T> {}

    record Held<T>(PendingChange change) implements Outcome<T> {}

    static <T> Outcome<T> applied(T value) {
        return new Applied<>(value);
    }

    static <T> Outcome<T> held(PendingChange change) {
        return new Held<>(change);
    }

    /**
     * The applied value. A legacy caller that cannot take a hold fails closed: it runs in the same transaction,
     * so the pending row it would have left rolls back with it.
     */
    default T appliedOrThrow() {
        if (this instanceof Applied<T> a) return a.value();
        throw new IllegalStateException("The change is held for approval (#"
                + ((Held<T>) this).change().getId() + ") and was not applied");
    }

    default <R> Outcome<R> map(Function<? super T, ? extends R> f) {
        if (this instanceof Applied<T> a) return applied(f.apply(a.value()));
        return held(((Held<T>) this).change());
    }

    default Optional<PendingChange> heldChange() {
        return this instanceof Held<T> h ? Optional.of(h.change()) : Optional.empty();
    }
}

package com.geneinvoice.common.scope;

import java.util.function.Supplier;

/** The as-of half of the bound {@link ViewScope}. */
public final class AsOf {

    private AsOf() {}

    public static AsOfPoint current() {
        return ViewScopeHolder.current().asOf();
    }

    public static boolean active() {
        return current() != null;
    }

    public static <T> T with(AsOfPoint p, Supplier<T> body) {
        return ViewScopeHolder.with(ViewScopeHolder.current().withAsOf(p), body);
    }
}

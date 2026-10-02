package com.geneinvoice.common.scope;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * What one request (or one background unit of work) is asking about: which regions ({@code null} means
 * every region the caller holds) and as of when ({@code null} means live).
 */
public record ViewScope(Set<Long> regionIds, AsOfPoint asOf) {

    public static final ViewScope LIVE_ALL = new ViewScope(null, null);

    public ViewScope {
        regionIds = regionIds == null ? null : Collections.unmodifiableSortedSet(new TreeSet<>(regionIds));
    }

    public Set<Long> narrow(Set<Long> mine) {
        if (regionIds == null) return mine;
        Set<Long> both = new TreeSet<>(mine);
        both.retainAll(regionIds);
        return Collections.unmodifiableSet(both);
    }

    public boolean historical() {
        return asOf != null;
    }

    public ViewScope withAsOf(AsOfPoint p) {
        return new ViewScope(regionIds, p);
    }
}

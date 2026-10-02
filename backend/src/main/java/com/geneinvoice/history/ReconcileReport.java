package com.geneinvoice.history;

import java.util.ArrayList;
import java.util.List;

/**
 * What a comparison of the live tables with their open versions found. Each entry names a record as
 * {@code "CODE id"}; {@code repaired} counts the records a reconcile wrote versions for (0 for a check).
 */
public record ReconcileReport(int checked, List<String> missing, List<String> drifted, List<String> orphaned,
                              List<String> duplicates, List<String> linkDrift, int repaired) {

    public ReconcileReport {
        missing = List.copyOf(missing);
        drifted = List.copyOf(drifted);
        orphaned = List.copyOf(orphaned);
        duplicates = List.copyOf(duplicates);
        linkDrift = List.copyOf(linkDrift);
    }

    /** Every finding, each prefixed with its kind; empty when history is in step with the live tables. */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        missing.forEach(m -> problems.add("missing " + m));
        drifted.forEach(d -> problems.add("drifted " + d));
        orphaned.forEach(o -> problems.add("orphaned " + o));
        duplicates.forEach(d -> problems.add("duplicate " + d));
        linkDrift.forEach(l -> problems.add("link " + l));
        return problems;
    }
}

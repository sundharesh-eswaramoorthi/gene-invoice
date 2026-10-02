package com.geneinvoice.automation.placeholder;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The values a record's placeholders stand for, worked out only when asked (a customer's outstanding balance costs a
 * query), each once, and always live: they describe what is done now (D10). POC placeholders are left blank for
 * someone who may not see who looks after a record, and a customer's fields other than its name for someone who may
 * not open the customer (SC-1, RR-1).
 */
public final class PlaceholderValues {

    private final Map<String, Supplier<String>> sources;
    private final boolean canSeePoc;
    private final boolean customerHidden;
    private final Map<String, Optional<String>> known = new HashMap<>();

    public PlaceholderValues(Map<String, Supplier<String>> sources, boolean canSeePoc) {
        this(sources, canSeePoc, false);
    }

    /**
     * {@code customerHidden}: the record's customer cannot be opened by whoever the values are for, so only its name
     * is among the sources and the other Customer placeholders come out empty with a warning.
     */
    public PlaceholderValues(Map<String, Supplier<String>> sources, boolean canSeePoc, boolean customerHidden) {
        this.sources = sources;
        this.canSeePoc = canSeePoc;
        this.customerHidden = customerHidden;
    }

    public boolean canSeePoc() {
        return canSeePoc;
    }

    public boolean customerHidden() {
        return customerHidden;
    }

    /** The value of a key this record has; empty when it has none or it is blank. */
    public Optional<String> get(String key) {
        return known.computeIfAbsent(key, k -> {
            Supplier<String> source = sources.get(k);
            String value = source == null ? null : source.get();
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
        });
    }
}

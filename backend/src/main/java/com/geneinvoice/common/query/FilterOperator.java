package com.geneinvoice.common.query;

import com.geneinvoice.common.BadRequestException;

import java.util.Arrays;
import java.util.Locale;

public enum FilterOperator {
    EQ("eq", 1),
    NEQ("neq", 1),
    CONTAINS("contains", 1),
    IN("in", -1),
    NOT_IN("notIn", -1),
    GT("gt", 1),
    GTE("gte", 1),
    LT("lt", 1),
    LTE("lte", 1),
    BETWEEN("between", 2),
    IS_EMPTY("isEmpty", 0),
    IS_NOT_EMPTY("isNotEmpty", 0),
    RELATIVE("relative", 1);

    private final String wire;
    private final int arity;

    FilterOperator(String wire, int arity) {
        this.wire = wire;
        this.arity = arity;
    }

    public String wire() {
        return wire;
    }

    public int arity() {
        return arity;
    }

    public boolean multiValued() {
        return arity != 1;
    }

    public static FilterOperator parse(String raw) {
        String needle = raw == null ? "" : raw.trim();
        return Arrays.stream(values())
                .filter(o -> o.wire.equalsIgnoreCase(needle) || o.name().equalsIgnoreCase(needle))
                .findFirst()
                .orElseThrow(() -> new BadRequestException(
                        "Unknown filter operator: " + raw.toLowerCase(Locale.ROOT)));
    }
}

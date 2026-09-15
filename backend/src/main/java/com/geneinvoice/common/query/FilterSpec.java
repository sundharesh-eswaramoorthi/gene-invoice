package com.geneinvoice.common.query;

import com.geneinvoice.common.BadRequestException;

import java.util.Arrays;
import java.util.List;

/**
 * One filter chip. Wire form is {@code field:operator:value}, where value is a comma-separated
 * list for multi-value operators and a single verbatim string otherwise (so a `contains` term may
 * itself contain commas and colons).
 */
public record FilterSpec(String field, FilterOperator operator, List<String> values) {

    public static FilterSpec parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("Empty filter");
        }
        int firstColon = raw.indexOf(':');
        if (firstColon <= 0) {
            throw new BadRequestException("Malformed filter (expected field:operator:value): " + raw);
        }
        int secondColon = raw.indexOf(':', firstColon + 1);
        if (secondColon < 0) {
            throw new BadRequestException("Malformed filter (expected field:operator:value): " + raw);
        }
        String field = raw.substring(0, firstColon).trim();
        FilterOperator op = FilterOperator.parse(raw.substring(firstColon + 1, secondColon));
        String rest = raw.substring(secondColon + 1);

        List<String> values;
        if (op.arity() == 0) {
            values = List.of();
        } else if (op.multiValued()) {
            values = Arrays.stream(rest.split(",", -1)).map(String::trim).filter(s -> !s.isEmpty()).toList();
        } else {
            values = List.of(rest);
        }

        if (op.arity() > 0 && values.size() != op.arity()) {
            throw new BadRequestException(
                    "Operator " + op.wire() + " expects " + op.arity() + " value(s) but got " + values.size());
        }
        if (op.arity() == -1 && values.isEmpty()) {
            throw new BadRequestException("Operator " + op.wire() + " expects at least one value");
        }
        return new FilterSpec(field, op, values);
    }

    public String first() {
        return values.isEmpty() ? null : values.get(0);
    }

    public String wire() {
        return field + ":" + operator.wire() + ":" + String.join(",", values);
    }
}

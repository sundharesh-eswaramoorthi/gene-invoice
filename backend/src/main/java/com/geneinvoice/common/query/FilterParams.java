package com.geneinvoice.common.query;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Arrays;
import java.util.List;

/**
 * Reads the repeated {@code filter} query parameter straight off the request.
 *
 * <p>Binding it as a {@code List<String>} would let Spring's default converter split a single
 * value on commas, which silently mangles the multi-value operators — {@code total:between:100,500}
 * would arrive as {@code total:between:100} plus a stray {@code 500}.
 */
public final class FilterParams {

    public static final String NAME = "filter";

    private FilterParams() {}

    public static List<String> from(HttpServletRequest request) {
        String[] raw = request.getParameterValues(NAME);
        if (raw == null) return List.of();
        return Arrays.stream(raw).filter(s -> s != null && !s.isBlank()).toList();
    }
}

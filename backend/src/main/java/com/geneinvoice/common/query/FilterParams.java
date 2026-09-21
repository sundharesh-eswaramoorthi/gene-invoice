package com.geneinvoice.common.query;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Arrays;
import java.util.List;

public final class FilterParams {

    public static final String NAME = "filter";

    private FilterParams() {}

    public static List<String> from(HttpServletRequest request) {
        String[] raw = request.getParameterValues(NAME);
        if (raw == null) return List.of();
        return Arrays.stream(raw).filter(s -> s != null && !s.isBlank()).toList();
    }
}

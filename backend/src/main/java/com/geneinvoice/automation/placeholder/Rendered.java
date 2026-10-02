package com.geneinvoice.automation.placeholder;

import java.util.List;

/** Text with its placeholders filled in, and what was worth saying about it (blank values, a cut). */
public record Rendered(String text, List<String> warnings) {
    public Rendered {
        warnings = List.copyOf(warnings);
    }
}

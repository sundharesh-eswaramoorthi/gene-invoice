package com.geneinvoice.automation.action;

import com.geneinvoice.email.EmailDtos.EmailToken;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What an action worked out before any lock (§5.10.6): its texts with placeholders filled in and its dates and
 * amounts ({@code values}, by field), the assignees kept once those who cannot see the record are dropped, why it
 * could not act ({@code problems}) and what is worth saying about it ({@code warnings}). {@code plan} is whatever
 * else the handler's own write needs from before the lock (who is to be told, say); a preview never shows it.
 */
public record Prepared(Map<String, Object> values, List<EmailToken> assignees, List<String> problems,
                       List<String> warnings, Object plan) {

    public Prepared {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        assignees = List.copyOf(assignees);
        problems = List.copyOf(problems);
        warnings = List.copyOf(warnings);
    }

    public Prepared(Map<String, Object> values, List<EmailToken> assignees, List<String> problems,
                    List<String> warnings) {
        this(values, assignees, problems, warnings, null);
    }
}

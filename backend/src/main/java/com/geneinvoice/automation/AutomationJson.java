package com.geneinvoice.automation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geneinvoice.common.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Reads and writes the two JSON columns a rule carries. It exists so that the authoring path, the
 * matcher and the actions all read a rule the same way: a rule that was accepted when it was
 * written must read back the same on every run, and a rule that no longer reads must fail as a
 * stated reason on the run rather than as a stack trace on a background thread (R9).
 */
@Component
@RequiredArgsConstructor
public class AutomationJson {

    private static final TypeReference<List<String>> FILTERS = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    public String write(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BadRequestException("This rule could not be saved: " + e.getMessage());
        }
    }

    /** The WHERE chips. A rule with none matches every record of its kind, which is a real thing to want. */
    public List<String> filters(AutomationRule rule) {
        String raw = rule.getFiltersJson();
        if (raw == null || raw.isBlank()) return List.of();
        try {
            List<String> chips = objectMapper.readValue(raw, FILTERS);
            return chips == null ? List.of() : chips.stream().filter(c -> c != null && !c.isBlank()).toList();
        } catch (Exception e) {
            throw new BadRequestException("This rule's filters could not be read: " + e.getMessage());
        }
    }

    /** The THEN's parameters. Never null: an action with nothing set is an empty spec, not a null check. */
    public AutomationDtos.ActionSpec action(AutomationRule rule) {
        String raw = rule.getActionJson();
        if (raw == null || raw.isBlank()) {
            return new AutomationDtos.ActionSpec(null, null, null, null, null, List.of(), null);
        }
        try {
            AutomationDtos.ActionSpec spec = objectMapper.readValue(raw, AutomationDtos.ActionSpec.class);
            return spec == null
                    ? new AutomationDtos.ActionSpec(null, null, null, null, null, List.of(), null)
                    : spec;
        } catch (Exception e) {
            throw new BadRequestException("This rule's action could not be read: " + e.getMessage());
        }
    }
}

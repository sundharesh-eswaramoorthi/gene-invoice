package com.geneinvoice.common.query;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.geneinvoice.common.BadRequestException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A {@link Condition} on the wire and in {@code automation_rules.condition_json}:
 * {@code {"op":"AND","children":[{"filter":"status:in:UNPAID"},{"op":"OR","children":[...]}]}}. Null means every
 * record.
 */
public final class ConditionJson {

    private ConditionJson() {}

    /**
     * The tree the client sent, or null for none. What cannot be read goes into {@code errors} under its path
     * ({@code condition}, {@code condition.children[0].filter}, ...), and null comes back.
     */
    public static Condition parse(JsonNode node, String path, Map<String, String> errors) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        int before = errors.size();
        Condition c = read(node, path, errors);
        return errors.size() == before ? c : null;
    }

    private static Condition read(JsonNode node, String path, Map<String, String> errors) {
        if (!node.isObject()) {
            errors.put(path, "A condition is a filter or a group");
            return null;
        }
        if (node.has("filter")) {
            JsonNode filter = node.get("filter");
            if (!filter.isTextual()) {
                errors.put(path + ".filter", "A filter is written field:operator:value");
                return null;
            }
            try {
                return new Condition.Leaf(FilterSpec.parse(filter.asText()));
            } catch (BadRequestException e) {
                errors.put(path + ".filter", e.getMessage());
                return null;
            }
        }
        Condition.Junction op = junction(node.get("op"));
        if (op == null) {
            errors.put(path + ".op", "A group is AND or OR");
            return null;
        }
        JsonNode children = node.get("children");
        if (children == null || !children.isArray()) {
            errors.put(path, "A group needs at least one condition");
            return null;
        }
        List<Condition> parsed = new ArrayList<>();
        for (int i = 0; i < children.size(); i++) {
            Condition child = read(children.get(i), path + ".children[" + i + "]", errors);
            if (child != null) parsed.add(child);
        }
        return new Condition.Group(op, parsed);
    }

    private static Condition.Junction junction(JsonNode op) {
        if (op == null || !op.isTextual()) return null;
        String wanted = op.asText().trim().toUpperCase(Locale.ROOT);
        for (Condition.Junction j : Condition.Junction.values()) {
            if (j.name().equals(wanted)) return j;
        }
        return null;
    }

    public static JsonNode write(Condition c, ObjectMapper mapper) {
        if (c == null) return null;
        ObjectNode out = mapper.createObjectNode();
        if (c instanceof Condition.Leaf leaf) {
            out.put("filter", leaf.spec().wire());
            return out;
        }
        Condition.Group group = (Condition.Group) c;
        out.put("op", group.op().name());
        ArrayNode children = out.putArray("children");
        group.children().forEach(child -> children.add(write(child, mapper)));
        return out;
    }

    /** A stored tree, written by {@link #write}; null for none. */
    public static Condition read(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) return null;
        try {
            Map<String, String> errors = new LinkedHashMap<>();
            Condition c = parse(mapper.readTree(json), "condition", errors);
            if (!errors.isEmpty()) throw new BadRequestException("Stored condition is not valid: " + errors);
            return c;
        } catch (JsonProcessingException e) {
            throw new BadRequestException("Stored condition is not valid JSON");
        }
    }

    /** The list page's own filter chips for an AND-only tree, left to right. */
    public static List<String> filters(Condition c) {
        if (c == null) return List.of();
        return c.leaves().stream().map(l -> l.spec().wire()).toList();
    }
}

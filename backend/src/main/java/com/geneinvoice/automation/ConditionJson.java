package com.geneinvoice.automation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.query.ConditionNode;
import com.geneinvoice.common.query.Conditions;
import com.geneinvoice.common.query.FilterSpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The one wire edge of the condition tree (A2).
 *
 * <p>Jackson lives HERE and not beside {@link ConditionNode}, so {@code com.geneinvoice.common.query}
 * stays Jackson-free as it is today. The format is identical on the API and in
 * {@code automation_rules.condition_json}, so what a rule was saved with is what a rule is
 * answered with:
 *
 * <pre>
 * {"op":"AND","of":[
 *     {"filter":"status:in:UNPAID,PARTIALLY_PAID"},
 *     {"op":"OR","of":[
 *         {"filter":"overdue:eq:true"},
 *         {"filter":"balance:gt:50000"}]}]}
 * </pre>
 *
 * <p>A leaf's text is the EXACT {@code FilterSpec.wire()} string: byte-identical to a
 * {@code ?filter=} query parameter, to what {@code TableFilter.wire} produces in Flutter and to
 * what {@code describeFilter} renders back. A malformed one therefore produces FilterSpec's own
 * existing 400 rather than a second vocabulary for the same mistake (A2).
 */
@Component
@RequiredArgsConstructor
public class ConditionJson {

    /** How much of an offending node a 400 echoes back. Hostile input is as long as the request
     *  body allows, and an error message is not a mirror (A2). */
    private static final int NODE_ECHO = 120;

    private final ObjectMapper objectMapper;

    /** Null or blank is "no conditions", which is a rule that matches every record in scope. */
    public ConditionNode parse(String json) {
        if (json == null || json.isBlank()) return null;
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            // A stored condition_json that will not parse is still the author's input, so it is a
            // 400 and not a 500 (A2).
            throw new BadRequestException("Conditions are not valid JSON: " + e.getOriginalMessage());
        }
        return parse(root);
    }

    public ConditionNode parse(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        return node(node, 1);
    }

    public String write(ConditionNode node) {
        if (node == null) return null;
        try {
            return objectMapper.writeValueAsString(json(node, 1));
        } catch (JsonProcessingException e) {
            // Nothing in a ConditionNode can fail to serialise; this cannot happen without a bug.
            throw new IllegalStateException("Conditions could not be written", e);
        }
    }

    private ConditionNode node(JsonNode n, int depth) {
        // The parser's own cap, one level looser than the business limit that
        // Conditions.validate enforces: this exists only so hostile input is refused with a 400
        // rather than killing the thread with a StackOverflowError (A2).
        if (depth > Conditions.MAX_DEPTH + 1) {
            throw new BadRequestException(
                    "Conditions may not be nested more than " + Conditions.MAX_DEPTH + " deep");
        }
        if (n == null || !n.isObject()) throw offending(n);

        if (n.hasNonNull("filter")) {
            JsonNode filter = n.get("filter");
            if (!filter.isTextual()) throw offending(n);
            // FilterSpec.parse, not a private splitter: a leaf and a filter-bar chip are the same
            // string and must be refused by the same code (A2).
            return new ConditionNode.Leaf(FilterSpec.parse(filter.asText()));
        }
        if (n.hasNonNull("op")) {
            ConditionNode.Connector op = connector(n.get("op"));
            List<ConditionNode> children = new ArrayList<>();
            JsonNode of = n.get("of");
            if (of != null && !of.isNull()) {
                if (!of.isArray()) {
                    throw new BadRequestException(
                            "A condition group's \"of\" must be a list: " + describe(n));
                }
                for (JsonNode child : of) children.add(node(child, depth + 1));
            }
            return new ConditionNode.Group(op, children);
        }
        throw offending(n);
    }

    private JsonNode json(ConditionNode node, int depth) {
        // Symmetric with the parser: a tree built in memory rather than parsed still cannot take
        // the writer past the stack (A2).
        if (depth > Conditions.MAX_DEPTH + 1) {
            throw new BadRequestException(
                    "Conditions may not be nested more than " + Conditions.MAX_DEPTH + " deep");
        }
        ObjectNode out = objectMapper.createObjectNode();
        if (node instanceof ConditionNode.Leaf leaf) {
            // spec.wire() and not the text it was parsed from: what comes back out is the chip the
            // filter bar would have produced, so a saved rule and a typed filter are one string (A2).
            out.put("filter", leaf.spec().wire());
            return out;
        }
        ConditionNode.Group g = (ConditionNode.Group) node;
        out.put("op", g.op().name());
        ArrayNode of = out.putArray("of");
        for (ConditionNode child : g.of()) of.add(json(child, depth + 1));
        return out;
    }

    private static ConditionNode.Connector connector(JsonNode op) {
        String raw = op.isTextual() ? op.asText().trim() : op.toString();
        try {
            return ConditionNode.Connector.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown condition connector: " + raw
                    + " (expected AND or OR)");
        }
    }

    private static BadRequestException offending(JsonNode n) {
        return new BadRequestException(
                "A condition must be either a filter or a group: " + describe(n));
    }

    private static String describe(JsonNode n) {
        String text = n == null ? "null" : n.toString();
        return text.length() <= NODE_ECHO ? text : text.substring(0, NODE_ECHO) + "...";
    }
}

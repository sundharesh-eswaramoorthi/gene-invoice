package com.geneinvoice.automation.action;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A rule's actions on the wire and in {@code automation_rules.actions_json}: a list of objects told apart by
 * {@code type}. Reading what a client sent reports each problem under the action's path ({@code actions[2].title}).
 */
public final class RuleActions {

    private RuleActions() {}

    public static final int MAX = 5;
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9-]{1,36}$");

    /**
     * The actions the client sent, by their place in the list, each with a key (one is made up for an action without
     * one) and a repeat. An unreadable action is left out, with its problem in {@code errors}.
     */
    public static Map<Integer, RuleAction> parse(List<JsonNode> nodes, ObjectMapper mapper,
                                                 Map<String, String> errors) {
        Map<Integer, RuleAction> out = new LinkedHashMap<>();
        if (nodes == null || nodes.isEmpty() || nodes.size() > MAX) {
            errors.put("actions", "A rule does 1 to " + MAX + " things");
            if (nodes == null) return out;
        }
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            String path = "actions[" + i + "]";
            JsonNode node = nodes.get(i);
            if (node == null || !node.isObject()) {
                errors.put(path, "An action is an object with a type");
                continue;
            }
            ActionType type = type(node.get("type"));
            if (type == null) {
                errors.put(path + ".type", "type must be one of " + List.of(ActionType.values()));
                continue;
            }
            RuleAction action;
            try {
                action = mapper.treeToValue(node, classOf(type));
            } catch (JsonMappingException e) {
                errors.put(path + fieldOf(e), "is not valid");
                continue;
            } catch (JsonProcessingException e) {
                errors.put(path, "is not valid");
                continue;
            }
            String key = action.key() == null || action.key().isBlank() ? UUID.randomUUID().toString()
                    : action.key().trim();
            if (!KEY.matcher(key).matches()) {
                errors.put(path + ".key", "A key is at most 36 letters, digits and dashes");
            } else if (keys.contains(key)) {
                errors.put(path + ".key", "Two actions have the same key");
            }
            keys.add(key);
            out.put(i, action.withKey(key, action.repeat() == null ? Repeat.ONCE_PER_RECORD : action.repeat()));
        }
        return out;
    }

    private static ActionType type(JsonNode raw) {
        if (raw == null || !raw.isTextual()) return null;
        String wanted = raw.asText().trim().toUpperCase(Locale.ROOT);
        for (ActionType t : ActionType.values()) {
            if (t.name().equals(wanted)) return t;
        }
        return null;
    }

    private static String fieldOf(JsonMappingException e) {
        return e.getPath().stream()
                .map(ref -> ref.getFieldName() != null ? "." + ref.getFieldName() : "[" + ref.getIndex() + "]")
                .collect(Collectors.joining());
    }

    static Class<? extends RuleAction> classOf(ActionType type) {
        return switch (type) {
            case CREATE_TASK -> CreateTaskAction.class;
            case CREATE_PROMISE -> CreatePromiseAction.class;
            case CREATE_DISPUTE -> CreateDisputeAction.class;
            case SEND_EMAIL -> SendEmailAction.class;
        };
    }

    /** The actions as the API shows them: each one's fields, with its {@code type}. */
    public static ArrayNode tree(List<RuleAction> actions, ObjectMapper mapper) {
        ArrayNode out = mapper.createArrayNode();
        for (RuleAction a : actions) {
            ObjectNode node = mapper.createObjectNode();
            node.put("key", a.key());
            node.put("type", a.type().name());
            ObjectNode fields = mapper.valueToTree(a);
            fields.remove("key");
            node.setAll(fields);
            out.add(node);
        }
        return out;
    }

    public static String write(List<RuleAction> actions, ObjectMapper mapper) {
        return tree(actions, mapper).toString();
    }

    /** Stored actions, written by {@link #write}. */
    public static List<RuleAction> read(String json, ObjectMapper mapper) {
        try {
            List<RuleAction> out = new ArrayList<>();
            for (JsonNode node : mapper.readTree(json)) {
                out.add(mapper.treeToValue(node, classOf(ActionType.valueOf(node.get("type").asText()))));
            }
            return out;
        } catch (JsonProcessingException | RuntimeException e) {
            throw new IllegalStateException("Stored actions are not valid: " + e.getMessage(), e);
        }
    }

    /** The same actions read from a tree the API or a revision wrote. */
    public static List<RuleAction> read(JsonNode tree, ObjectMapper mapper) {
        return read(tree == null ? "[]" : tree.toString(), mapper);
    }
}

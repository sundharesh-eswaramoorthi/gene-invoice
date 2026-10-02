package com.geneinvoice.approval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns a gated request into the JSON a pending change keeps, and back (§5.11.2). No password is ever kept: every
 * key naming one is dropped, however deep. Reading back ignores properties the request has since lost and leaves
 * new ones null; {@link #validate} re-checks the request's constraints. Either failure is a
 * {@link PayloadException}, which fails the approval rather than the server.
 */
@Component
public class PayloadCodec {

    private final ObjectMapper mapper;
    private final Validator validator;

    public PayloadCodec(ObjectMapper objectMapper, Validator validator) {
        this.mapper = objectMapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.validator = validator;
    }

    public String toJson(Object value) {
        if (value == null) return null;
        JsonNode tree = mapper.valueToTree(value);
        stripPasswords(tree);
        try {
            return mapper.writeValueAsString(tree);
        } catch (JsonProcessingException e) {
            throw new PayloadException("The change could not be stored: " + e.getOriginalMessage(), e);
        }
    }

    public <T> T read(String json, Class<T> type) {
        if (json == null) return null;
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new PayloadException("The stored change can no longer be read: " + e.getOriginalMessage(), e);
        }
    }

    /** The stored JSON as a tree, for showing it; null stays null. */
    public JsonNode tree(String json) {
        if (json == null) return null;
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new PayloadException("The stored change can no longer be read: " + e.getOriginalMessage(), e);
        }
    }

    public void validate(Object request) {
        Set<ConstraintViolation<Object>> violations = validator.validate(request);
        if (violations.isEmpty()) return;
        throw new PayloadException("The stored change is no longer valid: " + violations.stream()
                .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .collect(Collectors.joining("; ")));
    }

    private static void stripPasswords(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            for (Iterator<String> names = obj.fieldNames(); names.hasNext(); ) {
                if (names.next().toLowerCase(Locale.ROOT).contains("password")) names.remove();
            }
            obj.elements().forEachRemaining(PayloadCodec::stripPasswords);
        } else if (node != null && node.isArray()) {
            node.forEach(PayloadCodec::stripPasswords);
        }
    }
}

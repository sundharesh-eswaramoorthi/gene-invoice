package com.geneinvoice.mail.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * The message for each invalid field, keyed by its path in the request body, in the order found. The
 * exception's own message joins the different messages, for a caller that shows only that.
 */
public class InvalidFieldsException extends RuntimeException {

    private final Map<String, String> fieldErrors;

    public InvalidFieldsException(Map<String, String> fieldErrors) {
        super(String.join("; ", new LinkedHashSet<>(fieldErrors.values())));
        this.fieldErrors = Collections.unmodifiableMap(new LinkedHashMap<>(fieldErrors));
    }

    public Map<String, String> fieldErrors() {
        return fieldErrors;
    }
}

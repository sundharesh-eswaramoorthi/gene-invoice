package com.geneinvoice.mail.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

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

package com.geneinvoice.automation.placeholder;

import com.geneinvoice.automation.rule.TriggerEntity;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.Fit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills in {@code {{Customer.Name}}} and the like (A4, §5.10.4). One pass: a value is never read again for
 * placeholders of its own, so a customer called "{{Rule.Name}}" is printed as it is. {@code {{Key|fallback}}}
 * prints the fallback when the value is blank. Everything is plain text.
 */
public final class PlaceholderRenderer {

    private PlaceholderRenderer() {}

    /** Said once per text when a Customer placeholder other than the name is asked of a customer that is hidden. */
    public static final String CUSTOMER_HIDDEN =
            "Customer details are left out: you cannot see this record's customer";

    private static final String CUSTOMER_PREFIX = "Customer.";
    private static final String CUSTOMER_NAME = "Customer.Name";

    public static final Pattern TOKEN =
            Pattern.compile("\\{\\{\\s*([A-Za-z]+(?:\\.[A-Za-z]+)?)\\s*(?:\\|([^{}|]{0,100}))?\\s*\\}\\}");

    /** A placeholder that is none, found when a rule acts: the rule's definition is at fault, not the record. */
    public static class UnknownPlaceholderException extends BadRequestException {
        public UnknownPlaceholderException(String message) {
            super(message);
        }
    }

    /** Whether {@code text} may hold a placeholder at all; text without one is left exactly as written. */
    public static boolean mayHavePlaceholders(String text) {
        return text != null && text.contains("{{");
    }

    /** Checks at save that every placeholder in {@code text} is one offered on rules about {@code entity}. */
    public static void validate(String text, TriggerEntity entity, String path, Map<String, String> errors) {
        validate(text, PlaceholderCatalogue.offered(entity), entity.noun() + " rules", path, errors);
    }

    /**
     * Checks that every placeholder in {@code text} is one of {@code offered}; the first that is not is reported
     * under {@code path}, saying it is not available on {@code where} ("invoice emails").
     */
    public static void validate(String text, List<PlaceholderCatalogue.Item> offered, String where, String path,
                                Map<String, String> errors) {
        if (text == null) return;
        Matcher m = TOKEN.matcher(text);
        while (m.find()) {
            String key = m.group(1);
            Optional<PlaceholderCatalogue.Item> item = PlaceholderCatalogue.find(key);
            if (item.isEmpty()) {
                errors.put(path, "Unknown placeholder {{" + key + "}}");
                return;
            }
            if (!offered.contains(item.get())) {
                errors.put(path, item.get().token() + " is not available on " + where);
                return;
            }
        }
    }

    /**
     * {@code text} with its placeholders filled in from {@code values}, made one line when asked, and cut to
     * {@code max} characters with "…"; {@code field} names the text in warnings ("Subject").
     */
    public static Rendered render(String text, PlaceholderValues values, int max, boolean oneLine, String field) {
        if (text == null) return new Rendered(null, List.of());
        List<String> warnings = new ArrayList<>();
        Matcher m = TOKEN.matcher(text);
        StringBuilder out = new StringBuilder();
        boolean customerLeftOut = false;
        while (m.find()) {
            PlaceholderCatalogue.Item item = PlaceholderCatalogue.find(m.group(1))
                    .orElseThrow(() -> new UnknownPlaceholderException("Unknown placeholder {{" + m.group(1) + "}}"));
            String fallback = m.group(2) == null ? null : m.group(2).trim();
            if (values.customerHidden() && !customerLeftOut && item.key().startsWith(CUSTOMER_PREFIX)
                    && !item.key().equals(CUSTOMER_NAME)) {
                customerLeftOut = true;
                warnings.add(CUSTOMER_HIDDEN);
            }
            String value;
            if (item.poc() && !values.canSeePoc()) {
                value = fallback == null ? "" : fallback;
                warnings.add(item.token() + " is left out: POC details are not visible here");
            } else {
                Optional<String> found = values.get(item.key());
                if (found.isPresent()) {
                    value = found.get();
                } else if (fallback != null && !fallback.isEmpty()) {
                    value = fallback;
                } else {
                    value = "";
                    warnings.add(item.key() + " is empty");
                }
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        String result = oneLine ? out.toString().replaceAll("\\r\\n|\\r|\\n", " ").trim() : out.toString();
        if (result.length() > max) {
            result = Fit.to(result, max);
            warnings.add(field + " was cut to " + max + " characters");
        }
        return new Rendered(result, warnings);
    }
}

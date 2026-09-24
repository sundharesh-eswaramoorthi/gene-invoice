package com.geneinvoice.region;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The one region every account that predates regions belongs to. Named in configuration rather
 * than hard-coded so an operator can call it something their company recognises, but the row
 * itself is seeded once by code: renaming it after the backfill is a data change, not a config
 * change, because nothing goes looking for the old code again (B1).
 */
@Component
@ConfigurationProperties(prefix = "app.regions")
@Getter
@Setter
public class RegionProperties {

    private String defaultCode = "HQ";

    private String defaultName = "Head Office";

    // Checked at startup rather than at the first insert: both values go straight into columns of
    // a fixed width, and a truncation discovered during the backfill would leave half the
    // customers placed and half not (B1).
    @PostConstruct
    void resolve() {
        defaultCode = require(defaultCode, "REGION_DEFAULT_CODE", 20);
        defaultName = require(defaultName, "REGION_DEFAULT_NAME", 120);
    }

    private static String require(String value, String variable, int max) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalStateException("Set " + variable + " to the name of a region");
        }
        if (trimmed.length() > max) {
            throw new IllegalStateException(variable + " cannot be longer than " + max + " characters");
        }
        return trimmed;
    }

    public String defaultCode() {
        return defaultCode;
    }

    public String defaultName() {
        return defaultName;
    }
}

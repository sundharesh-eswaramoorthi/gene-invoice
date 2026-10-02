package com.geneinvoice.automation;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@code app.automation.*} (§5.15). Setters are public so a test can change a limit for one case and put it back.
 */
@Component
@ConfigurationProperties(prefix = "app.automation")
@Getter
@Setter
public class AutomationProperties {

    private boolean consumerEnabled = true;
    private long pollIntervalMs = 2000;
    private long tickIntervalMs = 30000;
    private int batchSize = 50;
    private int leaseSeconds = 300;
    private int maxAttempts = 5;
    private int maxClaims = 20;
    private int pageSize = 500;
    private long sliceBudgetMs = 10000;
    private int defaultMaxRecordsPerRun = 1000;
    private int maxActionsPerRulePerHour = 1000;
    private int maxEmailRecipients = 50;
    private int maxCatchUpSlots = 31;
    private int eventRetentionDays = 14;
    /** The zone a new rule's schedule and its {@code {{Today}}} are in unless it names one. */
    private String defaultZone = "UTC";
    private String nodeId = "";
    /** How many records a preview lists at most. */
    private int maxPreviewSample = 20;
}

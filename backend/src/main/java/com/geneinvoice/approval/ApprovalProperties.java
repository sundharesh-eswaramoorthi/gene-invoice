package com.geneinvoice.approval;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * The deployment's own answer to "hold what, and how much of it at once". The InvoiceProperties
 * shape: a @Component bound to a prefix, with short accessors beside Lombok's getters so call
 * sites read as facts rather than as bean plumbing (B2).
 */
@Component
@ConfigurationProperties(prefix = "app.approvals")
@Getter
@Setter
public class ApprovalProperties {

    // The yaml is ${APPROVAL_DEFAULT_THRESHOLD:}, and an empty value binds to null rather than to
    // zero: UNSET means maker-checker holds nothing in a region with no row of its own, which is
    // what makes the feature ship dormant and turning it on a deployment decision (B2).
    private BigDecimal defaultThreshold;

    // One bulk click must not turn 5000 rows (TableQueryExecutor.BULK_ID_LIMIT) into 5000
    // approvals somebody now has to decide one at a time (B2).
    private int bulkPendingLimit = 50;

    public BigDecimal defaultThreshold() {
        return defaultThreshold;
    }

    public int bulkPendingLimit() {
        return bulkPendingLimit;
    }
}

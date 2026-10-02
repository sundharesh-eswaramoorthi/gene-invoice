package com.geneinvoice.automation.run;

import org.springframework.stereotype.Component;

/** Actions as they run in production: nothing is made to fail. */
@Component
public class NoAutomationFaults implements AutomationFaults {

    @Override
    public void afterLock(Long ruleId, String actionKey, String targetType, Long targetId) {
        // nothing to inject
    }

    @Override
    public void beforeAction(Long ruleId, String actionKey, String targetType, Long targetId) {
        // nothing to inject
    }

    @Override
    public void afterActionCommitted(Long ruleId, String actionKey, String targetType, Long targetId) {
        // nothing to inject
    }
}

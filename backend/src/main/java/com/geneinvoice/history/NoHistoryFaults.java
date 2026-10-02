package com.geneinvoice.history;

import org.springframework.stereotype.Component;

/** Capture as it runs in production: nothing is made to fail. */
@Component
public class NoHistoryFaults implements HistoryFaults {

    @Override
    public void beforeSnapshot(String code, Long id) {
        // nothing to inject
    }
}

package com.geneinvoice.common.time;

import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
class AppClockBridge {

    AppClockBridge(Clock clock) {
        AppClock.set(clock);
    }
}

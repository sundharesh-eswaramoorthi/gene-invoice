package com.geneinvoice.common.time;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    // The only Clock bean in main code. Deliberately not @Primary, so a test's @Primary clock wins
    // without bean-definition overriding.
    @Bean
    public Clock appClock() {
        return Clock.systemUTC();
    }
}

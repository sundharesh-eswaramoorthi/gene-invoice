package com.geneinvoice.common.time;

import java.time.Clock;

/**
 * Protocol time: JWT issue and expiry, webhook signature skew and {@code ApiError.timestamp}. Never the
 * business clock, so a test that moves {@code appClock} cannot mint expired tokens or reject webhooks.
 */
public final class WallClock {

    public static final Clock UTC = Clock.systemUTC();

    private WallClock() {}
}

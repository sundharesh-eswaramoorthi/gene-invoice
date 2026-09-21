package com.geneinvoice.email.transport;

public record SyncResult(boolean enabled, int fetched, int imported, String error) {}

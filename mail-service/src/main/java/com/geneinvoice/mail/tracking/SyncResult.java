package com.geneinvoice.mail.tracking;

public record SyncResult(boolean enabled, int fetched, int imported, String error) {}

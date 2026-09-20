package com.geneinvoice.mail.connection;

/** The body of {@code PUT /api/v1/connections/{ownerRef}}: the three values the owner pasted, and their name. */
public record ConnectRequest(String ownerName, String clientId, String clientSecret, String refreshToken) {}

package com.geneinvoice.mail.connection;

public record ConnectRequest(String ownerName, String clientId, String clientSecret, String refreshToken) {}

package com.geneinvoice.auth.dto;

import java.util.List;

public record LoginResponse(
        String token,
        long expiresInMs,
        UserInfo user
) {
    public record UserInfo(
            Long id,
            String username,
            String fullName,
            String role,
            List<String> privileges,
            Long customerId
    ) {}
}

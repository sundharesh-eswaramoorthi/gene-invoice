package com.geneinvoice.auth.dto;

import java.util.List;

public record LoginResponse(
        String token,
        long expiresInMs,
        UserInfo user
) {
    /**
     * {@code privileges} is the union across regions, global ones included; {@code regions} lists each region
     * the user holds a grant in, with the regional privileges effective there ({@code []} for a customer login).
     */
    public record UserInfo(
            Long id,
            String username,
            String fullName,
            String role,
            List<String> privileges,
            Long customerId,
            List<RegionAccessDto> regions
    ) {}

    public record RegionAccessDto(
            Long id,
            String code,
            String name,
            boolean active,
            boolean defaultRegion,
            String level,
            String scope,
            List<String> privileges
    ) {}
}

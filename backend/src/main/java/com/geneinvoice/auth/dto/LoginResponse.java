package com.geneinvoice.auth.dto;

import com.geneinvoice.region.RegionDtos;

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
            // Unchanged on the wire and now read as "exercisable somewhere": a privilege this
            // person can use in no region is not in their authority set and is not listed here,
            // so a button the server would refuse is never offered (B1).
            List<String> privileges,
            Long customerId,
            // Extended, never redefined. allRegions is the null-region wildcard grant; regions
            // names only the branches this person actually holds something in, so [] beside
            // allRegions:false is a real state and not a missing field (B1).
            boolean allRegions,
            List<RegionDtos.RegionGrantDto> regions
    ) {}
}

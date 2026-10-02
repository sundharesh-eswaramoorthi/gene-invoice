package com.geneinvoice.region;

import com.geneinvoice.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class RegionDtos {
    private RegionDtos() {}

    public record RegionDto(Long id, String code, String name, boolean active, boolean defaultRegion,
                            Long version, Instant createdAt) {
        public static RegionDto from(Region r) {
            return new RegionDto(r.getId(), r.getCode(), r.getName(), r.isActive(), r.isDefaultRegion(),
                    r.getVersion(), r.getCreatedAt());
        }
    }

    /** {@code code} is checked in the service, after trimming, so a bad one is reported like a taken one. */
    public record CreateRegionRequest(
            @NotBlank String code,
            @NotBlank @Size(max = 120) String name,
            Boolean active
    ) {}

    /** {@code code} is immutable, so it is not taken here. */
    public record UpdateRegionRequest(
            @NotBlank @Size(max = 120) String name,
            Boolean active,
            @NotNull Long version
    ) {}

    /** One row of a user's region access, as it is asked for: on user create and on the grants PUT. */
    public record GrantInput(@NotNull Long regionId, @NotNull AccessLevel level, @NotNull RecordScope scope) {}

    public record ReplaceGrantsRequest(@NotNull List<@Valid @NotNull GrantInput> grants) {}

    /** A user's grant, as the user's Regions tab and {@code UserDto.regionGrants} show it. */
    public record UserGrantDto(Long regionId, String regionCode, String regionName, String level, String scope,
                               Instant grantedAt, Long grantedByUserId) {
        public static UserGrantDto from(UserRegionGrant g) {
            return new UserGrantDto(g.getRegion().getId(), g.getRegion().getCode(), g.getRegion().getName(),
                    g.getAccessLevel().name(), g.getRecordScope().name(), g.getGrantedAt(), g.getGrantedByUserId());
        }
    }

    /** Who holds what in one region: the region page's Grants tab. */
    public record RegionGrantHolderDto(Long userId, String username, String fullName, String roleName,
                                       String level, String scope) {
        public static RegionGrantHolderDto from(UserRegionGrant g) {
            User u = g.getUser();
            return new RegionGrantHolderDto(u.getId(), u.getUsername(), u.getFullName(),
                    u.getRole() == null ? null : u.getRole().getName(),
                    g.getAccessLevel().name(), g.getRecordScope().name());
        }
    }
}

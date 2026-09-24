package com.geneinvoice.poc;

import com.geneinvoice.user.User;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public class PocDtos {

    public record PocUserDto(Long id, String username, String fullName, String email,
                             String role, boolean active) {
        public static PocUserDto from(User u) {
            if (u == null) return null;
            return new PocUserDto(u.getId(), u.getUsername(), u.getFullName(), u.getEmail(),
                    u.getRole() == null ? null : u.getRole().getName(), u.isActive());
        }
    }

    public record CustomerPocDto(Long id, PocType pocType, boolean primary,
                                 PocUserDto user, Instant createdAt) {
        public static CustomerPocDto from(CustomerPoc p) {
            return new CustomerPocDto(p.getId(), p.getPocType(), p.isPrimary(),
                    PocUserDto.from(p.getUser()), p.getCreatedAt());
        }

        /**
         * The same seat, as it stood on the date asked about (B3).
         *
         * <p>A second factory rather than a widened first parameter, because {@code CustomerPoc}
         * has no *View interface: B3-VIEWS wrote one for each of the six LIST entities and a seat
         * is not one of them. {@code primary} is boxed on the mirror — every business column on
         * every mirror is nullable so a tombstone can be written — and a null reads as false, the
         * same answer the live primitive gives for a seat nobody made primary.
         *
         * <p>The person renders as they are TODAY: {@code user} is a read-only association to the
         * live row and {@code User} is not mirrored, which is contract clause a.3 and the same
         * rule the sales POC's name on an invoice follows.
         */
        public static CustomerPocDto from(CustomerPocHistory p) {
            return new CustomerPocDto(p.getId(), p.getPocType(), Boolean.TRUE.equals(p.getPrimary()),
                    PocUserDto.from(p.getUser()), p.getCreatedAt());
        }
    }

    public record AddCustomerPocRequest(
            @NotNull PocType pocType,
            @NotNull Long userId,
            Boolean primary
    ) {}
}

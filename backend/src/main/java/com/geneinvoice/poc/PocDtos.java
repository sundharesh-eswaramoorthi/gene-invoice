package com.geneinvoice.poc;

import com.geneinvoice.user.User;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public class PocDtos {

    /** A user as offered in an assignment dropdown, or as recorded on a record. */
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
    }

    public record AddCustomerPocRequest(
            @NotNull PocType pocType,
            @NotNull Long userId,
            Boolean primary
    ) {}
}

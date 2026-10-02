package com.geneinvoice.privilege;

import com.geneinvoice.common.scope.AsOfIgnored;
import com.geneinvoice.region.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@AsOfIgnored
@RequestMapping("/api/privileges")
@RequiredArgsConstructor
public class PrivilegeController {

    private final PrivilegeRepository repository;

    /** {@code regionLevel}: the least grant level it needs to take effect in a region; null for a global one. */
    public record PrivilegeDto(Long id, String name, String description, String regionLevel) {
        static PrivilegeDto from(Privilege p) {
            AccessLevel level = Privileges.requiredLevel(p.getName());
            return new PrivilegeDto(p.getId(), p.getName(), p.getDescription(), level == null ? null : level.name());
        }
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Privileges.ROLE_VIEW + "')")
    public List<PrivilegeDto> list() {
        return repository.findAll().stream().map(PrivilegeDto::from).toList();
    }
}

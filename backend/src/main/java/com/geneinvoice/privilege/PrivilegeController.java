package com.geneinvoice.privilege;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/privileges")
@RequiredArgsConstructor
public class PrivilegeController {

    private final PrivilegeRepository repository;

    public record PrivilegeDto(Long id, String name, String description) {
        static PrivilegeDto from(Privilege p) {
            return new PrivilegeDto(p.getId(), p.getName(), p.getDescription());
        }
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Privileges.ROLE_VIEW + "')")
    public List<PrivilegeDto> list() {
        return repository.findAll().stream().map(PrivilegeDto::from).toList();
    }
}

package com.geneinvoice.role;

import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.privilege.Privilege;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleRepository roleRepository;
    private final PrivilegeRepository privilegeRepository;

    public record RoleDto(Long id, String name, String description, List<String> privileges) {
        static RoleDto from(Role r) {
            return new RoleDto(r.getId(), r.getName(), r.getDescription(),
                    r.getPrivileges().stream().map(Privilege::getName).sorted().toList());
        }
    }

    public record RoleUpsert(
            @NotBlank String name,
            String description,
            List<String> privileges
    ) {}

    @GetMapping
    @PreAuthorize("hasAuthority('" + Privileges.ROLE_VIEW + "')")
    public List<RoleDto> list() {
        return roleRepository.findAll().stream().map(RoleDto::from).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.ROLE_VIEW + "')")
    public RoleDto get(@PathVariable Long id) {
        return roleRepository.findById(id).map(RoleDto::from)
                .orElseThrow(() -> new NotFoundException("Role not found"));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Privileges.ROLE_MANAGE + "')")
    public RoleDto create(@Valid @RequestBody RoleUpsert in) {
        Role r = Role.builder()
                .name(in.name()).description(in.description())
                .privileges(resolvePrivileges(in.privileges()))
                .build();
        return RoleDto.from(roleRepository.save(r));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.ROLE_MANAGE + "')")
    public RoleDto update(@PathVariable Long id, @Valid @RequestBody RoleUpsert in) {
        Role r = roleRepository.findById(id).orElseThrow(() -> new NotFoundException("Role not found"));
        r.setName(in.name());
        r.setDescription(in.description());
        r.setPrivileges(resolvePrivileges(in.privileges()));
        return RoleDto.from(roleRepository.save(r));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.ROLE_MANAGE + "')")
    public void delete(@PathVariable Long id) {
        roleRepository.deleteById(id);
    }

    private Set<Privilege> resolvePrivileges(List<String> names) {
        if (names == null || names.isEmpty()) return new HashSet<>();
        return new HashSet<>(names.stream()
                .map(n -> privilegeRepository.findByName(n)
                        .orElseThrow(() -> new NotFoundException("Privilege not found: " + n)))
                .toList());
    }
}

package com.geneinvoice.role;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.bulk.BulkDtos;
import com.geneinvoice.common.bulk.Csv;
import com.geneinvoice.common.query.FilterParams;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.privilege.Privilege;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    private final TableQueryExecutor queryExecutor;

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
    public PageResponse<RoleDto> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            HttpServletRequest request) {
        TableQuery query = TableQuery.parse(TableSchemas.ROLES, page, size, sort, FilterParams.from(request));
        var result = queryExecutor.run(Role.class, TableSchemas.ROLES, query, List.of(), List.of());
        return PageResponse.of(result.content().stream().map(RoleDto::from).toList(),
                query, result.total(), List.of());
    }

    @PostMapping("/export")
    @PreAuthorize("hasAuthority('" + Privileges.EXPORT_DATA + "') and hasAuthority('" + Privileges.ROLE_VIEW + "')")
    public ResponseEntity<String> export(@RequestBody BulkDtos.BulkRequest req) {
        TableQuery query = TableQuery.parseUnpaged(TableSchemas.ROLES, req.sort(), req.filters());
        List<Long> permitted = queryExecutor.ids(Role.class, TableSchemas.ROLES, query,
                List.of(), TableQueryExecutor.BULK_ID_LIMIT);
        List<Long> ids = req.allMatching() ? permitted
                : (req.ids() == null ? List.<Long>of()
                        : req.ids().stream().filter(permitted::contains).toList());
        if (ids.isEmpty() && !req.allMatching()) {
            throw new BadRequestException("Provide ids or set selectAllMatchingFilter");
        }
        String csv = Csv.of(List.of("Id", "Name", "Description", "Privileges"),
                roleRepository.findAllById(ids).stream().map(r -> List.<Object>of(
                        r.getId(), r.getName(), r.getDescription() == null ? "" : r.getDescription(),
                        String.join(" ", r.getPrivileges().stream().map(Privilege::getName).sorted().toList())
                )).toList());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"roles.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv);
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

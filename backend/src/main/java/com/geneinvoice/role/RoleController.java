package com.geneinvoice.role;

import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.bulk.BulkDtos;
import com.geneinvoice.common.bulk.Csv;
import com.geneinvoice.common.query.FilterParams;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.email.connection.GmailDisconnects;
import com.geneinvoice.privilege.Privilege;
import com.geneinvoice.privilege.PrivilegeRepository;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleRepository roleRepository;
    private final PrivilegeRepository privilegeRepository;
    private final UserRepository userRepository;
    private final TableQueryExecutor queryExecutor;
    private final GmailDisconnects gmailDisconnects;
    private final CurrentUser currentUser;

    public record RoleDto(Long id, String name, String description, List<String> privileges) {
        static RoleDto from(Role r) {
            return new RoleDto(r.getId(), r.getName(), r.getDescription(),
                    r.getPrivileges().stream().map(Privilege::getName).sorted().toList());
        }
    }

    public record RoleUpsert(
            @NotBlank @Size(max = FieldLimits.ROLE_NAME) String name,
            @Size(max = FieldLimits.ROLE_DESCRIPTION) String description,
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
        // findAllById ignores order, so put the rows back the way the filter and sort asked (D-41).
        List<Role> roles = new ArrayList<>(roleRepository.findAllById(ids));
        roles.sort(Comparator.comparingInt(r -> ids.indexOf(r.getId())));
        String csv = Csv.of(List.of("Id", "Name", "Description", "Privileges"),
                roles.stream().map(r -> List.<Object>of(
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
        String name = in.name().trim();
        if (roleRepository.existsByNameIgnoreCase(name)) {
            throw new BadRequestException("Role name already exists");
        }
        Role r = Role.builder()
                .name(name).description(in.description())
                .privileges(resolvePrivileges(in.privileges()))
                .build();
        return RoleDto.from(roleRepository.save(r));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.ROLE_MANAGE + "')")
    public RoleDto update(@PathVariable Long id, @Valid @RequestBody RoleUpsert in) {
        Role r = roleRepository.findById(id).orElseThrow(() -> new NotFoundException("Role not found"));
        String name = in.name().trim();
        if (!name.equalsIgnoreCase(r.getName()) && roleRepository.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw new BadRequestException("Role name already exists");
        }
        Set<Privilege> wanted = resolvePrivileges(in.privileges());
        requireAdministrationSurvives(id, r, wanted);
        boolean sentEmail = sendsEmail(r);
        r.setName(name);
        r.setDescription(in.description());
        r.setPrivileges(wanted);
        Role saved = roleRepository.save(r);
        if (sentEmail && !sendsEmail(saved)) {
            // Its holders may no longer send email, so their Gmail connections go (mail-service.md §5.6).
            gmailDisconnects.request(userRepository.findInternalIdsByRoleId(id));
        }
        return RoleDto.from(saved);
    }

    private static boolean sendsEmail(Role r) {
        return r.getPrivileges().stream().anyMatch(p -> Privileges.EMAIL_SEND.equals(p.getName()));
    }

    /**
     * The role editor is the third way to end user and role administration, beside deactivating
     * and deleting the account itself (AUTH-03). Taking USER_MANAGE or ROLE_MANAGE off one's own
     * role locks the editor out the moment it is saved — the very request that would put them
     * back is refused — and taking USER_MANAGE off the last role that carries it leaves the whole
     * deployment with nobody able to administer users. Neither is recoverable from inside the app.
     */
    private void requireAdministrationSurvives(Long roleId, Role role, Set<Privilege> wanted) {
        Set<String> after = wanted.stream().map(Privilege::getName).collect(Collectors.toSet());
        Set<String> before = role.getPrivileges().stream().map(Privilege::getName)
                .collect(Collectors.toSet());
        var me = currentUser.require();
        boolean ownRole = me.getCustomerId() == null && me.getRole() != null
                && roleId.equals(me.getRole().getId());
        for (String administration : List.of(Privileges.USER_MANAGE, Privileges.ROLE_MANAGE)) {
            if (!before.contains(administration) || after.contains(administration)) continue;
            if (ownRole) {
                throw new BadRequestException(
                        "You cannot remove your own ability to manage users and roles");
            }
        }
        // Nobody on another role is left to administer users: the same last-administrator rule
        // the user editor applies, asked of everyone this role would strip at once.
        if (before.contains(Privileges.USER_MANAGE) && !after.contains(Privileges.USER_MANAGE)
                && userRepository.countActiveHolders(Privileges.USER_MANAGE, null, roleId) == 0) {
            throw new BadRequestException("This is the last role that can manage users;"
                    + " give another role that ability first");
        }
    }

    /** A role still held by users cannot go: their accounts would be left without privileges. */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.ROLE_MANAGE + "')")
    public void delete(@PathVariable Long id) {
        Role r = roleRepository.findById(id).orElseThrow(() -> new NotFoundException("Role not found"));
        long holders = userRepository.countByRoleId(id);
        if (holders > 0) {
            throw new BadRequestException("Role is assigned to " + holders
                    + (holders == 1 ? " user" : " users") + "; move them to another role first");
        }
        roleRepository.delete(r);
    }

    private Set<Privilege> resolvePrivileges(List<String> names) {
        if (names == null || names.isEmpty()) return new HashSet<>();
        return new HashSet<>(names.stream()
                .map(n -> privilegeRepository.findByName(n)
                        .orElseThrow(() -> new NotFoundException("Privilege not found: " + n)))
                .toList());
    }
}

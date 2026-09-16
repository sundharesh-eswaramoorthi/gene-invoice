package com.geneinvoice.user;

import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.Emails;
import com.geneinvoice.common.Passwords;
import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.bulk.BulkDtos;
import com.geneinvoice.common.bulk.BulkExecutor;
import com.geneinvoice.common.bulk.Csv;
import com.geneinvoice.common.query.FilterParams;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.payment.PaymentRepository;
import com.geneinvoice.poc.CustomerPocRepository;
import com.geneinvoice.privilege.Privileges;
import jakarta.servlet.http.HttpServletRequest;
import com.geneinvoice.promise.PaymentPromiseRepository;
import com.geneinvoice.role.Role;
import com.geneinvoice.role.RoleRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final TableQueryExecutor queryExecutor;
    private final BulkExecutor bulkExecutor;
    private final AuditService auditService;
    private final CurrentUser currentUser;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentPromiseRepository promiseRepository;
    private final CustomerPocRepository customerPocRepository;

    public record UserDto(Long id, String username, String email, String fullName,
                          boolean active, String role, Long customerId,
                          List<String> privileges) {
        static UserDto from(User u) {
            return new UserDto(u.getId(), u.getUsername(), u.getEmail(), u.getFullName(),
                    u.isActive(), u.getRole() == null ? null : u.getRole().getName(),
                    u.getCustomerId(),
                    u.getRole() == null ? List.of()
                            : u.getRole().getPrivileges().stream()
                                    .map(com.geneinvoice.privilege.Privilege::getName).sorted().toList());
        }
    }

    public record CreateUserRequest(
            @NotBlank @Size(max = FieldLimits.USERNAME) String username,
            @Email @Size(max = FieldLimits.EMAIL) String email,
            @Size(max = FieldLimits.FULL_NAME) String fullName,
            @NotBlank String password,
            @NotNull Long roleId,
            Boolean active
    ) {}

    public record UpdateUserRequest(
            @Email @Size(max = FieldLimits.EMAIL) String email,
            @Size(max = FieldLimits.FULL_NAME) String fullName,
            String password,
            Long roleId,
            Boolean active
    ) {}

    @GetMapping
    @PreAuthorize("hasAuthority('" + Privileges.USER_VIEW + "')")
    public PageResponse<UserDto> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            HttpServletRequest request) {
        TableQuery query = TableQuery.parse(TableSchemas.USERS, page, size, sort, FilterParams.from(request));
        var result = queryExecutor.run(User.class, TableSchemas.USERS, query, List.of(), List.of("role"));
        return PageResponse.of(result.content().stream().map(UserDto::from).toList(),
                query, result.total(), List.of());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.USER_VIEW + "')")
    public UserDto get(@PathVariable Long id) {
        return userRepository.findById(id).map(UserDto::from)
                .orElseThrow(() -> new NotFoundException("User not found"));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Privileges.USER_MANAGE + "')")
    public UserDto create(@Valid @RequestBody CreateUserRequest in) {
        if (userRepository.existsByUsername(in.username())) {
            throw new BadRequestException("Username already exists");
        }
        String email = Emails.normalize(in.email());
        if (email != null && userRepository.existsByEmailIgnoreCase(email)) {
            throw new BadRequestException("Email already exists");
        }
        Passwords.require(in.password());
        Role role = roleRepository.findById(in.roleId())
                .orElseThrow(() -> new NotFoundException("Role not found"));
        if ("CUSTOMER".equalsIgnoreCase(role.getName())) {
            throw new BadRequestException("Use POST /api/customers to create a customer account");
        }
        User u = User.builder()
                .username(in.username())
                .email(email)
                .fullName(in.fullName())
                .password(passwordEncoder.encode(in.password()))
                .role(role)
                .active(in.active() == null || in.active())
                .build();
        return UserDto.from(userRepository.save(u));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.USER_MANAGE + "')")
    public UserDto update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest in) {
        User u = userRepository.findById(id).orElseThrow(() -> new NotFoundException("User not found"));
        Object before = UserDto.from(u);
        if (in.email() != null) {
            // Blank clears the email. Uniqueness is checked only on a change, so an account can
            // always be re-saved as it is.
            String email = Emails.normalize(in.email());
            if (email != null && !email.equalsIgnoreCase(u.getEmail())
                    && userRepository.existsByEmailIgnoreCaseAndIdNot(email, id)) {
                throw new BadRequestException("Email already exists");
            }
            u.setEmail(email);
        }
        if (in.fullName() != null) u.setFullName(in.fullName());
        if (in.password() != null && !in.password().isBlank()) {
            Passwords.require(in.password());
            u.setPassword(passwordEncoder.encode(in.password()));
        }
        if (in.roleId() != null) {
            Role role = roleRepository.findById(in.roleId())
                    .orElseThrow(() -> new NotFoundException("Role not found"));
            u.setRole(role);
        }
        if (in.active() != null) u.setActive(in.active());
        User saved = userRepository.save(u);
        auditService.record("USER", id, "USER_UPDATED", before, UserDto.from(saved),
                currentUser.require().getId(), null, null);
        return UserDto.from(saved);
    }

    /**
     * Deleting a user who owns POC assignments would orphan those records, so such a user is
     * deactivated instead: history stays readable and they drop out of the dropdowns (AC-A5).
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.USER_MANAGE + "')")
    public Map<String, Object> delete(@PathVariable Long id) {
        User u = userRepository.findById(id).orElseThrow(() -> new NotFoundException("User not found"));
        long references = pocReferenceCount(id);
        if (references > 0) {
            u.setActive(false);
            userRepository.save(u);
            auditService.record("USER", id, "USER_DEACTIVATED", null, UserDto.from(u),
                    currentUser.require().getId(), null,
                    "Deactivated instead of deleted: named as a POC on " + references + " record(s)");
            return Map.of("deleted", false, "deactivated", true, "pocReferences", references);
        }
        userRepository.deleteById(id);
        return Map.of("deleted", true, "deactivated", false, "pocReferences", 0);
    }

    private long pocReferenceCount(Long userId) {
        return invoiceRepository.countBySalesPocId(userId)
                + paymentRepository.countByCollectionPocId(userId)
                + promiseRepository.countByCollectionPocId(userId)
                + customerPocRepository.countByUserId(userId);
    }

    // ---- bulk & export ---------------------------------------------------------

    public static final List<String> BULK_ACTIONS = List.of("ACTIVATE", "DEACTIVATE");

    @PostMapping("/bulk")
    @PreAuthorize("hasAuthority('" + Privileges.USER_MANAGE + "')")
    public BulkDtos.BulkResult bulk(@Valid @RequestBody BulkDtos.BulkRequest req) {
        boolean activate = switch (req.action()) {
            case "ACTIVATE" -> true;
            case "DEACTIVATE" -> false;
            default -> throw new BadRequestException(
                    "Unknown bulk action: " + req.action() + " (expected one of " + BULK_ACTIONS + ")");
        };
        List<Long> ids = resolveIds(req);
        boolean truncated = req.allMatching() && ids.size() >= TableQueryExecutor.BULK_ID_LIMIT;
        Long actor = currentUser.require().getId();
        return bulkExecutor.run(req, ids, truncated, id -> {
            if (id.equals(actor)) {
                throw new BulkExecutor.IneligibleException("You cannot change your own account in bulk");
            }
            User u = userRepository.findById(id)
                    .orElseThrow(() -> new NotFoundException("User not found: " + id));
            if (u.isActive() == activate) {
                throw new BulkExecutor.IneligibleException(
                        "Already " + (activate ? "active" : "inactive"));
            }
            Object before = UserDto.from(u);
            u.setActive(activate);
            User saved = userRepository.save(u);
            auditService.record("USER", id, activate ? "USER_ACTIVATED" : "USER_DEACTIVATED",
                    before, UserDto.from(saved), actor, null, "Bulk action");
        });
    }

    @PostMapping("/export")
    @PreAuthorize("hasAuthority('" + Privileges.EXPORT_DATA + "') and hasAuthority('" + Privileges.USER_VIEW + "')")
    public ResponseEntity<String> export(@RequestBody BulkDtos.BulkRequest req) {
        List<Long> ids = resolveIds(req);
        // findAllById ignores order, so put the rows back the way the filter and sort asked (D-41).
        List<User> users = new ArrayList<>(userRepository.findAllById(ids));
        users.sort(Comparator.comparingInt(u -> ids.indexOf(u.getId())));
        String csv = Csv.of(List.of("Id", "Username", "Full name", "Email", "Role", "Active"),
                users.stream().map(u -> List.<Object>of(u.getId(), u.getUsername(),
                        u.getFullName() == null ? "" : u.getFullName(),
                        u.getEmail() == null ? "" : u.getEmail(),
                        u.getRole() == null ? "" : u.getRole().getName(), u.isActive())).toList());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"users.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv);
    }

    private List<Long> resolveIds(BulkDtos.BulkRequest req) {
        TableQuery query = TableQuery.parseUnpaged(TableSchemas.USERS, req.sort(), req.filters());
        List<Long> permitted = queryExecutor.ids(User.class, TableSchemas.USERS, query,
                List.of(), TableQueryExecutor.BULK_ID_LIMIT);
        if (req.allMatching()) return permitted;
        if (req.ids() == null || req.ids().isEmpty()) {
            throw new BadRequestException("Provide ids or set selectAllMatchingFilter");
        }
        return req.ids().stream().filter(permitted::contains).toList();
    }
}

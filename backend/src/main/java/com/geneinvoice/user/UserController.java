package com.geneinvoice.user;

import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.Emails;
import com.geneinvoice.common.Passwords;
import com.geneinvoice.common.Strings;
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
import com.geneinvoice.email.connection.GmailDisconnects;
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

import java.time.Instant;
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
    private final GmailDisconnects gmailDisconnects;

    public record UserDto(Long id, String username, String email, String fullName,
                          boolean active, String role, Long customerId,
                          List<String> privileges, Instant createdAt) {
        static UserDto from(User u) {
            return new UserDto(u.getId(), u.getUsername(), u.getEmail(), u.getFullName(),
                    u.isActive(), u.getRole() == null ? null : u.getRole().getName(),
                    u.getCustomerId(),
                    u.getRole() == null ? List.of()
                            : u.getRole().getPrivileges().stream()
                                    .map(com.geneinvoice.privilege.Privilege::getName).sorted().toList(),
                    u.getCreatedAt());
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
        // Trimmed before it is checked and before it is stored, so the account can be signed
        // into with the username its owner was given (CP-12).
        String username = Strings.trim(in.username());
        // Case-insensitively, so "ADMIN" cannot be minted beside "admin" and read as it in the
        // users list, the audit trail and every export that names people by username (CP-06).
        if (userRepository.existsByUsernameIgnoreCase(username)) {
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
                .username(username)
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
        boolean deactivating = Boolean.FALSE.equals(in.active()) && u.isActive();
        boolean movingRole = in.roleId() != null
                && (u.getRole() == null || !in.roleId().equals(u.getRole().getId()));
        // The same guard bulk() already applies, on the path a single PUT takes: an administrator
        // who locks themselves out this way cannot get back in, and nothing in the app can undo
        // it (AUTH-03). Every other edit of one's own account — name, email, password — is fine.
        if (id.equals(currentUser.require().getId()) && (deactivating || movingRole)) {
            throw new BadRequestException(
                    "You cannot deactivate or change the role of your own account");
        }
        if (deactivating || movingRole) requireAdministratorsRemain(id);
        Object before = UserDto.from(u);
        boolean couldSendEmail = GmailDisconnects.mayConnect(u);
        if (in.email() != null) {
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
            // An administrator resetting somebody's password ends that person's open sessions
            // too: it is the same act for the same reason (AUTH-04).
            u.setCredentialsChangedAt(Instant.now());
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
        if (couldSendEmail && !GmailDisconnects.mayConnect(saved)) gmailDisconnects.request(List.of(id));
        return UserDto.from(saved);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Privileges.USER_MANAGE + "')")
    public Map<String, Object> delete(@PathVariable Long id) {
        User u = userRepository.findById(id).orElseThrow(() -> new NotFoundException("User not found"));
        if (id.equals(currentUser.require().getId())) {
            throw new BadRequestException("You cannot delete your own account");
        }
        // A customer's login is created and removed with the customer; deleting it on its own
        // leaves a customer nobody can sign in as, and frees its email for a second customer to
        // take (CP-05).
        if (u.getCustomerId() != null) {
            throw new BadRequestException("This is a customer's login; delete the customer instead");
        }
        requireAdministratorsRemain(id);
        long references = pocReferenceCount(id);
        if (references > 0) {
            boolean couldSendEmail = GmailDisconnects.mayConnect(u);
            u.setActive(false);
            userRepository.save(u);
            auditService.record("USER", id, "USER_DEACTIVATED", null, UserDto.from(u),
                    currentUser.require().getId(), null,
                    "Deactivated instead of deleted: named as a POC on " + references + " record(s)");
            if (couldSendEmail) gmailDisconnects.request(List.of(id));
            return Map.of("deleted", false, "deactivated", true, "pocReferences", references);
        }
        boolean internal = u.getCustomerId() == null;
        Object before = UserDto.from(u);
        userRepository.deleteById(id);
        // The account is gone; the fact that somebody removed it is not (CP-04).
        auditService.record("USER", id, "USER_DELETED", before, null,
                currentUser.require().getId(), null, "User deleted");
        if (internal) gmailDisconnects.request(List.of(id));
        return Map.of("deleted", true, "deactivated", false, "pocReferences", 0);
    }

    private void requireAdministratorsRemain(Long excludedUserId) {
        if (userRepository.countActiveHolders(Privileges.USER_MANAGE, excludedUserId, null) == 0) {
            throw new BadRequestException("This is the last active account that can manage users;"
                    + " give another account that ability first");
        }
    }

    private long pocReferenceCount(Long userId) {
        return invoiceRepository.countBySalesPocId(userId)
                + paymentRepository.countByCollectionPocId(userId)
                + promiseRepository.countByCollectionPocId(userId)
                + customerPocRepository.countByUserId(userId);
    }

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
        List<Long> leftEmail = new ArrayList<>();
        BulkDtos.BulkResult result = bulkExecutor.run(req, ids, truncated, id -> {
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
            boolean couldSendEmail = GmailDisconnects.mayConnect(u);
            u.setActive(activate);
            User saved = userRepository.save(u);
            auditService.record("USER", id, activate ? "USER_ACTIVATED" : "USER_DEACTIVATED",
                    before, UserDto.from(saved), actor, null, "Bulk action");
            if (couldSendEmail && !GmailDisconnects.mayConnect(saved)) {
                gmailDisconnects.mark(List.of(id));
                leftEmail.add(id);
            }
        });
        gmailDisconnects.process(leftEmail);
        return result;
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

package com.geneinvoice.customer;

import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.Emails;
import com.geneinvoice.common.Passwords;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.query.Aggregates;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.email.EmailCleanup;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.poc.CustomerPoc;
import com.geneinvoice.poc.CustomerPocRepository;
import com.geneinvoice.poc.PocDtos;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.role.Role;
import com.geneinvoice.role.RoleRepository;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CustomerService {

    public static final String ENTITY = "CUSTOMER";

    private final CustomerRepository repository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final InvoiceRepository invoiceRepository;
    private final CustomerPocRepository customerPocRepository;
    private final PasswordEncoder passwordEncoder;
    private final ScopeResolver scopeResolver;
    private final TableQueryExecutor queryExecutor;
    private final AuditService auditService;
    private final CurrentUser currentUser;
    private final EmailCleanup emailCleanup;

    // ---- reads -----------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<CustomerDtos.CustomerDto> page(TableQuery query) {
        ScopeResolver.Scope scope = scopeResolver.forCustomers();
        var page = queryExecutor.run(Customer.class, TableSchemas.CUSTOMERS, query,
                scope.predicates(), List.of());
        return PageResponse.of(toDtos(page.content()), query, page.total(), scope.lockedFilters());
    }

    @Transactional(readOnly = true)
    public List<Long> idsMatching(TableQuery query, int limit) {
        return queryExecutor.ids(Customer.class, TableSchemas.CUSTOMERS, query,
                scopeResolver.forCustomers().predicates(), limit);
    }

    @Transactional(readOnly = true)
    public List<Customer> allMatching(TableQuery query) {
        return queryExecutor.run(Customer.class, TableSchemas.CUSTOMERS, query,
                scopeResolver.forCustomers().predicates(), List.of()).content();
    }

    @Transactional(readOnly = true)
    public CustomerDtos.CustomerSummaryTiles tiles(TableQuery query) {
        ScopeResolver.Scope scope = scopeResolver.forCustomers();
        Object[] row = queryExecutor.aggregate(Customer.class, TableSchemas.CUSTOMERS, query,
                scope.predicates(), (root, q, cb) -> List.of(
                        cb.count(root.get("id")),
                        cb.coalesce(cb.sum(outstandingSubquery(root, q, cb)), BigDecimal.ZERO),
                        cb.coalesce(cb.sum(root.<BigDecimal>get("creditBalance")), BigDecimal.ZERO),
                        Aggregates.countWhen(cb, cb.not(cb.exists(
                                seatSubquery(root, q, cb, PocType.SUCCESS)))),
                        Aggregates.countWhen(cb, cb.not(cb.exists(
                                seatSubquery(root, q, cb, PocType.COLLECTION))))));
        return new CustomerDtos.CustomerSummaryTiles(
                Aggregates.asLong(row[0]), Aggregates.asMoney(row[1]), Aggregates.asMoney(row[2]),
                Aggregates.asLong(row[3]), Aggregates.asLong(row[4]));
    }

    private jakarta.persistence.criteria.Expression<BigDecimal> outstandingSubquery(
            jakarta.persistence.criteria.Root<?> root,
            jakarta.persistence.criteria.CriteriaQuery<?> q,
            jakarta.persistence.criteria.CriteriaBuilder cb) {
        Subquery<BigDecimal> sq = q.subquery(BigDecimal.class);
        var inv = sq.from(com.geneinvoice.invoice.Invoice.class);
        sq.select(cb.coalesce(
                        cb.sum(cb.diff(inv.<BigDecimal>get("total"), inv.<BigDecimal>get("paidAmount"))),
                        cb.literal(BigDecimal.ZERO)))
                .where(cb.equal(inv.get("customer").get("id"), root.get("id")),
                        cb.notEqual(inv.get("status"), InvoiceStatus.CANCELLED));
        return sq;
    }

    private Subquery<Long> seatSubquery(jakarta.persistence.criteria.Root<?> root,
                                        jakarta.persistence.criteria.CriteriaQuery<?> q,
                                        jakarta.persistence.criteria.CriteriaBuilder cb,
                                        PocType type) {
        Subquery<Long> sq = q.subquery(Long.class);
        var seat = sq.from(CustomerPoc.class);
        sq.select(cb.literal(1L)).where(
                cb.equal(seat.get("customer").get("id"), root.get("id")),
                cb.equal(seat.get("pocType"), type));
        return sq;
    }

    @Transactional(readOnly = true)
    public Customer get(Long id) {
        Long callerCustomer = currentUser.customerIdOrNull();
        if (callerCustomer != null && !callerCustomer.equals(id)) {
            throw new AccessDeniedException("Not allowed");
        }
        Customer c = repository.findById(id).orElseThrow(() -> new NotFoundException("Customer not found"));
        requireInBook(id);
        return c;
    }

    /** A POC limited to their own book cannot reach, edit or re-seat other customers by id (AC-A6). */
    private void requireInBook(Long id) {
        if (!queryExecutor.inScope(Customer.class, TableSchemas.CUSTOMERS, id,
                scopeResolver.forCustomers().predicates())) {
            throw new NotFoundException("Customer not found");
        }
    }

    @Transactional(readOnly = true)
    public CustomerDtos.CustomerDto toDto(Customer c) {
        return toDtos(List.of(c)).get(0);
    }

    /** Builds DTOs for a page in a fixed number of queries rather than one per row. */
    @Transactional(readOnly = true)
    public List<CustomerDtos.CustomerDto> toDtos(List<Customer> customers) {
        if (customers.isEmpty()) return List.of();
        boolean showPoc = scopeResolver.canSeePoc();
        List<Long> ids = customers.stream().map(Customer::getId).toList();

        Map<Long, BigDecimal> outstanding = new HashMap<>();
        for (Object[] row : invoiceRepository.sumOutstandingByCustomer(ids)) {
            outstanding.put((Long) row[0], Aggregates.asMoney(row[1]));
        }

        Map<Long, String> usernames = new HashMap<>();
        for (Long id : ids) {
            userRepository.findByCustomerId(id).ifPresent(u -> usernames.put(id, u.getUsername()));
        }

        Map<Long, List<CustomerPoc>> seats = new LinkedHashMap<>();
        if (showPoc) {
            for (CustomerPoc seat : customerPocRepository.findByCustomerIdIn(ids)) {
                seats.computeIfAbsent(seat.getCustomer().getId(), k -> new ArrayList<>()).add(seat);
            }
        }

        return customers.stream().map(c -> {
            List<CustomerPoc> mine = seats.getOrDefault(c.getId(), List.of());
            List<PocDtos.CustomerPocDto> success = mine.stream()
                    .filter(s -> s.getPocType() == PocType.SUCCESS)
                    .map(PocDtos.CustomerPocDto::from).toList();
            List<PocDtos.CustomerPocDto> collection = mine.stream()
                    .filter(s -> s.getPocType() == PocType.COLLECTION)
                    .map(PocDtos.CustomerPocDto::from).toList();
            return new CustomerDtos.CustomerDto(
                    c.getId(), c.getName(), c.getPhone(), c.getEmail(),
                    List.copyOf(c.getAdditionalEmails()), c.getAddress(),
                    c.getCreditBalance(), usernames.get(c.getId()),
                    outstanding.getOrDefault(c.getId(), BigDecimal.ZERO),
                    showPoc ? success : null,
                    showPoc ? collection : null,
                    showPoc ? (success.isEmpty() || collection.isEmpty()) : null,
                    c.getCreatedAt());
        }).toList();
    }

    // ---- writes ----------------------------------------------------------------

    @Transactional
    public Customer create(CustomerDtos.CustomerCreateRequest in) {
        if (userRepository.existsByUsername(in.username())) {
            throw new BadRequestException("Username already taken");
        }
        // The login shares the customer's email, and user emails are unique.
        String email = Emails.normalize(in.email());
        if (email != null && userRepository.existsByEmailIgnoreCase(email)) {
            throw new BadRequestException("Email already exists");
        }
        Role customerRole = roleRepository.findByName("CUSTOMER")
                .orElseThrow(() -> new IllegalStateException("CUSTOMER role not seeded"));

        Customer c = repository.save(Customer.builder()
                .name(in.name()).phone(in.phone()).email(email).address(in.address())
                .additionalEmails(Emails.normalizeOthers(in.additionalEmails(), email))
                .build());

        Passwords.require(in.password());
        userRepository.save(User.builder()
                .username(in.username())
                .email(email)
                .fullName(in.name())
                .password(passwordEncoder.encode(in.password()))
                .role(customerRole)
                .customerId(c.getId())
                .active(true)
                .build());

        auditService.record(ENTITY, c.getId(), "CUSTOMER_CREATED", null, snapshot(c),
                currentUser.require().getId(), null, null);
        return c;
    }

    @Transactional
    public Customer update(Long id, CustomerDtos.CustomerUpdateRequest in) {
        Customer c = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Customer not found"));
        requireInBook(id);
        Object before = snapshot(c);
        String email = Emails.normalize(in.email());
        User linked = userRepository.findByCustomerId(id).orElse(null);
        // The login shares the customer's email, and user emails are unique.
        if (linked != null && email != null && !email.equalsIgnoreCase(linked.getEmail())
                && userRepository.existsByEmailIgnoreCaseAndIdNot(email, linked.getId())) {
            throw new BadRequestException("Email already exists");
        }
        c.setName(in.name());
        c.setPhone(in.phone());
        c.setEmail(email);
        List<String> others = in.additionalEmails() == null ? c.getAdditionalEmails() : in.additionalEmails();
        List<String> normalizedOthers = Emails.normalizeOthers(others, email);
        c.getAdditionalEmails().clear();
        c.getAdditionalEmails().addAll(normalizedOthers);
        c.setAddress(in.address());
        Customer saved = repository.save(c);

        if (linked != null) {
            linked.setFullName(in.name());
            linked.setEmail(email);
            if (in.password() != null && !in.password().isBlank()) {
                Passwords.require(in.password());
                linked.setPassword(passwordEncoder.encode(in.password()));
            }
            userRepository.save(linked);
        }
        auditService.record(ENTITY, id, "CUSTOMER_UPDATED", before, snapshot(saved),
                currentUser.require().getId(), null, null);
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        requireInBook(id);
        for (CustomerPoc seat : customerPocRepository.findByCustomerIdOrderByPocTypeAscPrimaryDescIdAsc(id)) {
            customerPocRepository.delete(seat);
        }
        userRepository.findByCustomerId(id).ifPresent(userRepository::delete);
        emailCleanup.deleteForCustomer(id);
        repository.deleteById(id);
    }

    private Object snapshot(Customer c) {
        return new CustomerAuditSnapshot(c.getId(), c.getName(), c.getPhone(), c.getEmail(),
                List.copyOf(c.getAdditionalEmails()), c.getAddress(), c.getCreditBalance());
    }

    public record CustomerAuditSnapshot(Long id, String name, String phone, String email,
                                        List<String> additionalEmails,
                                        String address, BigDecimal creditBalance) {}
}

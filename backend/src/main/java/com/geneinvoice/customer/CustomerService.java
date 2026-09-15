package com.geneinvoice.customer;

import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.query.Aggregates;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchemas;
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
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Customer not found"));
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
                    c.getId(), c.getName(), c.getPhone(), c.getEmail(), c.getAddress(),
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
        Role customerRole = roleRepository.findByName("CUSTOMER")
                .orElseThrow(() -> new IllegalStateException("CUSTOMER role not seeded"));

        Customer c = repository.save(Customer.builder()
                .name(in.name()).phone(in.phone()).email(in.email()).address(in.address())
                .build());

        userRepository.save(User.builder()
                .username(in.username())
                .email(in.email())
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
        Object before = snapshot(c);
        c.setName(in.name());
        c.setPhone(in.phone());
        c.setEmail(in.email());
        c.setAddress(in.address());
        Customer saved = repository.save(c);

        User linked = userRepository.findByCustomerId(id).orElse(null);
        if (linked != null) {
            linked.setFullName(in.name());
            linked.setEmail(in.email());
            if (in.password() != null && !in.password().isBlank()) {
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
        for (CustomerPoc seat : customerPocRepository.findByCustomerIdOrderByPocTypeAscPrimaryDescIdAsc(id)) {
            customerPocRepository.delete(seat);
        }
        userRepository.findByCustomerId(id).ifPresent(userRepository::delete);
        repository.deleteById(id);
    }

    private Object snapshot(Customer c) {
        return new CustomerAuditSnapshot(c.getId(), c.getName(), c.getPhone(), c.getEmail(),
                c.getAddress(), c.getCreditBalance());
    }

    public record CustomerAuditSnapshot(Long id, String name, String phone, String email,
                                        String address, BigDecimal creditBalance) {}
}

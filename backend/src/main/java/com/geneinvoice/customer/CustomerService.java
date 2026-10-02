package com.geneinvoice.customer;

import com.geneinvoice.approval.PendingChangeCloser;
import com.geneinvoice.audit.AuditService;
import com.geneinvoice.automation.event.AutomationEvents;
import com.geneinvoice.automation.rule.TriggerEntity;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.Emails;
import com.geneinvoice.common.Passwords;
import com.geneinvoice.common.Strings;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.query.Aggregates;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.common.GlobalExceptionHandler;
import com.geneinvoice.common.scope.AsOf;
import com.geneinvoice.common.scope.AsOfPoint;
import com.geneinvoice.common.scope.ViewScopeHolder;
import com.geneinvoice.document.DocumentCascade;
import com.geneinvoice.email.EmailCascade;
import com.geneinvoice.history.HistoryQueries;
import com.geneinvoice.history.Records;
import com.geneinvoice.history.Temporal;
import com.geneinvoice.invoice.InvoiceDates;
import com.geneinvoice.invoice.InvoiceProperties;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.invoice.PaymentTerm;
import com.geneinvoice.poc.CustomerPoc;
import com.geneinvoice.poc.CustomerPocRepository;
import com.geneinvoice.poc.PocDtos;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.region.RecordAccess;
import com.geneinvoice.region.Region;
import com.geneinvoice.region.RegionRepository;
import com.geneinvoice.region.RegionService;
import com.geneinvoice.role.Role;
import com.geneinvoice.role.RoleRepository;
import com.geneinvoice.task.TaskCascade;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

@Service
@RequiredArgsConstructor
public class CustomerService {

    public static final String ENTITY = "CUSTOMER";

    /** The POC tab's order within a type (the primary first, then oldest), live and as of a date alike. */
    private static final Comparator<CustomerPoc> SEAT_ORDER = Comparator
            .comparing((CustomerPoc s) -> !s.isPrimary())
            .thenComparing(CustomerPoc::getId);

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
    private final InvoiceProperties invoiceProperties;
    private final DocumentCascade documentCascade;
    private final EmailCascade emailCascade;
    private final TaskCascade taskCascade;
    private final PendingChangeCloser pendingChangeCloser;
    private final RegionRepository regionRepository;
    private final RegionService regionService;
    private final RecordAccess recordAccess;
    private final Records records;
    private final HistoryQueries historyQueries;
    private final AutomationEvents automationEvents;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<CustomerDtos.CustomerDto> page(TableQuery query) {
        ScopeResolver.Scope scope = scopeResolver.forCustomers();
        var page = queryExecutor.run(Customer.class, TableSchemas.CUSTOMERS, query,
                scope.predicates(), List.of("region"));
        return PageResponse.of(toDtos(page.content()), query, page.total(), scope);
    }

    /** The ids a bulk action or export may touch: VIEW for an export, MANAGE (and any extras) for a write. */
    @Transactional(readOnly = true)
    public List<Long> idsMatching(TableQuery query, int limit, ScopeResolver.Access access, String... alsoRequired) {
        return queryExecutor.ids(Customer.class, TableSchemas.CUSTOMERS, query,
                scopeResolver.scope(ScopeResolver.Kind.CUSTOMER, access, ScopeResolver.Reach.SELECTED, alsoRequired)
                        .predicates(), limit);
    }

    @Transactional(readOnly = true)
    public List<Customer> allMatching(TableQuery query) {
        return queryExecutor.run(Customer.class, TableSchemas.CUSTOMERS, query,
                scopeResolver.forCustomers().predicates(), List.of("region")).content();
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
        return Temporal.subquery(q, BigDecimal.class, com.geneinvoice.invoice.Invoice.class, cb, (inv, sq, where) -> {
            where.add(cb.equal(inv.get("customer").get("id"), root.get("id")));
            where.add(cb.notEqual(inv.get("status"), InvoiceStatus.CANCELLED));
            return cb.coalesce(
                    cb.sum(cb.diff(inv.<BigDecimal>get("total"), inv.<BigDecimal>get("paidAmount"))),
                    cb.literal(BigDecimal.ZERO));
        });
    }

    private Subquery<Long> seatSubquery(jakarta.persistence.criteria.Root<?> root,
                                        jakarta.persistence.criteria.CriteriaQuery<?> q,
                                        jakarta.persistence.criteria.CriteriaBuilder cb,
                                        PocType type) {
        return Temporal.subquery(q, Long.class, CustomerPoc.class, cb, (seat, sq, where) -> {
            where.add(cb.equal(seat.get("customer").get("id"), root.get("id")));
            where.add(cb.equal(seat.get("pocType"), type));
            return cb.literal(1L);
        });
    }

    /** The customer, or as of a date the customer as it was then (not found if it did not exist yet). */
    @Transactional(readOnly = true)
    public Customer get(Long id) {
        Long callerCustomer = currentUser.customerIdOrNull();
        if (callerCustomer != null && !callerCustomer.equals(id)) {
            throw new AccessDeniedException("Not allowed");
        }
        Customer c = records.find(Customer.class, id).orElseThrow(() -> new NotFoundException("Customer not found"));
        requireVisible(c);
        return c;
    }

    private void requireVisible(Customer c) {
        scopeResolver.requireVisible(ScopeResolver.Kind.CUSTOMER, c.getId(), Region.idOf(c.getRegion()),
                "Customer not found");
    }

    /**
     * A customer the caller may change, locked first so its region is read under the lock a region move takes
     * (§5.11.5, SC-7): one they cannot see is missing (404), one outside their managed regions a 403.
     */
    private Customer requireManageable(Long id) {
        Customer c = repository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Customer not found"));
        requireVisible(c);
        ViewScopeHolder.requireLive();
        currentUser.requireIn(Privileges.CUSTOMER_MANAGE, Region.idOf(c.getRegion()));
        return c;
    }

    @Transactional(readOnly = true)
    public CustomerDtos.CustomerDto toDto(Customer c) {
        return toDtos(List.of(c)).get(0);
    }

    /**
     * Customers with the figures they sum over their invoices and their POC seats. As of a date these come from
     * history too (CF-5): the invoices and seats as they were at the point, overdue on the viewed day.
     */
    @Transactional(readOnly = true)
    public List<CustomerDtos.CustomerDto> toDtos(List<Customer> customers) {
        if (customers.isEmpty()) return List.of();
        boolean showPoc = scopeResolver.canSeePoc();
        List<Long> ids = customers.stream().map(Customer::getId).toList();
        Set<Long> moveTargets = recordAccess.moveTargets();
        AsOfPoint point = AsOf.current();

        Map<Long, BigDecimal> outstanding = new HashMap<>();
        for (Object[] row : point == null
                ? invoiceRepository.sumOutstandingByCustomer(ids)
                : historyQueries.sumOutstandingByCustomerAt(ids, point)) {
            outstanding.put((Long) row[0], Aggregates.asMoney(row[1]));
        }

        Map<Long, BigDecimal> overdue = new HashMap<>();
        for (Object[] row : point == null
                ? invoiceRepository.sumOverdueByCustomer(ids, InvoiceDates.today())
                : historyQueries.sumOverdueByCustomerAt(ids, InvoiceDates.today(), point)) {
            overdue.put((Long) row[0], Aggregates.asMoney(row[1]));
        }

        Map<Long, String> usernames = new HashMap<>();
        for (Long id : ids) {
            userRepository.findByCustomerId(id).ifPresent(u -> usernames.put(id, u.getUsername()));
        }

        Map<Long, List<CustomerPoc>> seats = new LinkedHashMap<>();
        if (showPoc) {
            for (CustomerPoc seat : point == null
                    ? customerPocRepository.findByCustomerIdIn(ids)
                    : historyQueries.seatsAt(ids, point)) {
                seats.computeIfAbsent(seat.getCustomer().getId(), k -> new ArrayList<>()).add(seat);
            }
        }

        return customers.stream().map(c -> {
            List<CustomerPoc> mine = seats.getOrDefault(c.getId(), List.of());
            List<PocDtos.CustomerPocDto> success = mine.stream()
                    .filter(s -> s.getPocType() == PocType.SUCCESS)
                    .sorted(SEAT_ORDER)
                    .map(PocDtos.CustomerPocDto::from).toList();
            List<PocDtos.CustomerPocDto> collection = mine.stream()
                    .filter(s -> s.getPocType() == PocType.COLLECTION)
                    .sorted(SEAT_ORDER)
                    .map(PocDtos.CustomerPocDto::from).toList();
            PaymentTerm term = c.getPaymentTerm() == null
                    ? invoiceProperties.defaultTerm()
                    : c.getPaymentTerm();
            return new CustomerDtos.CustomerDto(
                    c.getId(), c.getName(), c.getPhone(), c.getEmail(), c.getAddress(),
                    c.getCreditBalance(), usernames.get(c.getId()),
                    c.getPaymentTerm(), term.label(),
                    outstanding.getOrDefault(c.getId(), BigDecimal.ZERO),
                    overdue.getOrDefault(c.getId(), BigDecimal.ZERO),
                    showPoc ? success : null,
                    showPoc ? collection : null,
                    showPoc ? (success.isEmpty() || collection.isEmpty()) : null,
                    c.getCreatedAt(),
                    Region.idOf(c.getRegion()), Region.codeOf(c.getRegion()), Region.nameOf(c.getRegion()),
                    recordAccess.canManage(ScopeResolver.Kind.CUSTOMER, Region.idOf(c.getRegion())),
                    recordAccess.canMoveRegion(Region.idOf(c.getRegion()), moveTargets),
                    c.getVersion());
        }).toList();
    }

    @Transactional
    public Customer create(CustomerDtos.CustomerCreateRequest in) {
        ViewScopeHolder.requireLive();
        // Trimmed before it is checked and before it is stored: an untrimmed username is one the
        // customer cannot sign in with, because they are told it without its spaces (CP-12).
        String username = Strings.trim(in.username());
        // Case-insensitively: a customer login called "ADMIN" would be indistinguishable from
        // the administrator's in every list that names people by username (CP-06).
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new BadRequestException("Username already taken");
        }
        // The login shares the customer's email, and user emails are unique. The customers are
        // checked too: a customer outlives a deleted login, and the freed address must not then
        // be reusable, or every email to either customer would land in one inbox (CP-05).
        String email = Emails.normalize(in.email());
        if (email != null
                && (userRepository.existsByEmailIgnoreCase(email)
                        || repository.existsByEmailIgnoreCase(email))) {
            throw new BadRequestException("Email already exists");
        }
        Role customerRole = roleRepository.findByName("CUSTOMER")
                .orElseThrow(() -> new IllegalStateException("CUSTOMER role not seeded"));
        Region region = regionFor(in.regionId());

        Customer c = repository.save(Customer.builder()
                // Trimmed, and a box the user left empty is stored as nothing rather than as an
                // empty string, so the list shows its "—" placeholder (CP-15).
                .name(Strings.trim(in.name())).phone(Strings.blankToNull(in.phone()))
                .email(email).address(Strings.blankToNull(in.address()))
                .paymentTerm(settableTerm(in.paymentTerm()))
                .region(region)
                .build());

        Passwords.require(in.password());
        userRepository.save(User.builder()
                .username(username)
                .email(email)
                .fullName(Strings.trim(in.name()))
                .password(passwordEncoder.encode(in.password()))
                .role(customerRole)
                .customerId(c.getId())
                .active(true)
                .credentialsSetByUserId(currentUser.idOrNull())
                .build());

        auditService.record(ENTITY, c.getId(), "CUSTOMER_CREATED", null, snapshotOf(c),
                currentUser.require().getId(), null, null);
        automationEvents.created(TriggerEntity.CUSTOMER, c.getId());
        return c;
    }

    @Transactional
    public Customer update(Long id, CustomerDtos.CustomerUpdateRequest in) {
        Customer c = requireManageable(id);
        Object before = snapshotOf(c);
        String email = Emails.normalize(in.email());
        User linked = userRepository.findByCustomerId(id).orElse(null);
        // The login shares the customer's email, and user emails are unique; no other customer
        // may hold it either, login or no login (CP-05).
        if (email != null && !email.equalsIgnoreCase(c.getEmail())
                && repository.existsByEmailIgnoreCaseAndIdNot(email, id)) {
            throw new BadRequestException("Email already exists");
        }
        if (linked != null && email != null && !email.equalsIgnoreCase(linked.getEmail())
                && userRepository.existsByEmailIgnoreCaseAndIdNot(email, linked.getId())) {
            throw new BadRequestException("Email already exists");
        }
        PaymentTerm previousTerm = c.getPaymentTerm();
        String previousName = c.getName();
        c.setName(Strings.trim(in.name()));
        c.setPhone(Strings.blankToNull(in.phone()));
        c.setEmail(email);
        c.setAddress(Strings.blankToNull(in.address()));
        c.setPaymentTerm(settableTerm(in.paymentTerm()));
        Customer saved = repository.save(c);

        if (linked != null) {
            linked.setFullName(Strings.trim(in.name()));
            linked.setEmail(email);
            if (in.password() != null && !in.password().isBlank()) {
                Passwords.require(in.password());
                linked.setPassword(passwordEncoder.encode(in.password()));
                // The customer's own open sessions end with the password they were signed in
                // with, exactly as a staff account's do (AUTH-04).
                linked.setCredentialsChangedAt(clock.instant());
                // Whoever set it could sign in as the customer (SC-10).
                linked.setCredentialsSetByUserId(currentUser.require().getId());
            }
            userRepository.save(linked);
        }
        auditService.record(ENTITY, id, "CUSTOMER_UPDATED", before, snapshotOf(saved),
                currentUser.require().getId(), null, null);
        if (previousTerm != saved.getPaymentTerm()) {
            auditService.record(ENTITY, id, "CUSTOMER_PAYMENT_TERM_CHANGED",
                    previousTerm == null ? null : previousTerm.name(),
                    saved.getPaymentTerm() == null ? null : saved.getPaymentTerm().name(),
                    currentUser.require().getId(), null, null);
        }
        automationEvents.updated(TriggerEntity.CUSTOMER, id, "DETAILS");
        // The customer's name is a column of the invoices and payments lists too (§5.8.3).
        if (!Objects.equals(previousName, saved.getName())) automationEvents.children(id, "CUSTOMER_NAME");
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        Customer c = requireManageable(id);
        // What is about to go, read before the cascade takes it (CP-04).
        Object before = snapshotOf(c);
        documentCascade.onCustomerDeleted(id);
        // The emails recorded against it stay — they are a record of something that was said —
        // but stop claiming the customer is still there to link to (CP-13).
        emailCascade.onCustomerDeleted(id);
        // Its tasks, and those on its invoices and payments, stay as a record of work, cancelled if still open.
        taskCascade.onCustomerDeleted(id);
        // Changes waiting on it have nothing left to change: closed, and their makers told (§5.11.7).
        pendingChangeCloser.closeForCustomer(id, "Customer deleted");
        for (CustomerPoc seat : customerPocRepository.findByCustomerIdOrderByPocTypeAscPrimaryDescIdAsc(id)) {
            customerPocRepository.delete(seat);
        }
        userRepository.findByCustomerId(id).ifPresent(userRepository::delete);
        repository.deleteById(id);
        // Removing a customer takes its login, its POC seats and every document on it and on its
        // invoices and payments with it. It is the most destructive write on this entity and was
        // the only one leaving no trail at all (CP-04).
        // The row carries the region the customer was in, so its history stays answerable by region (§5.6.5).
        auditService.record(ENTITY, id, "CUSTOMER_DELETED", before, null,
                currentUser.require().getId(), null, "Customer deleted", Region.idOf(c.getRegion()));
    }

    /**
     * The region a new customer goes in: the one asked for, or else the only active region the caller may
     * manage customers in (first among all of them, then among the regions this request is looking at).
     * An unreachable region is a 404, one the caller may not manage customers in a 403, an inactive one a 400.
     */
    private Region regionFor(Long requested) {
        Long regionId = requested;
        if (regionId == null) {
            Set<Long> managed = new TreeSet<>();
            for (Region r : regionRepository.findAllById(currentUser.regionsWith(Privileges.CUSTOMER_MANAGE))) {
                if (r.isActive()) managed.add(r.getId());
            }
            Set<Long> asked = ViewScopeHolder.current().narrow(managed);
            if (managed.size() == 1) regionId = managed.iterator().next();
            else if (asked.size() == 1) regionId = asked.iterator().next();
            else throw new GlobalExceptionHandler.InvalidFieldsException(
                    Map.of("regionId", "Choose the region this customer belongs to"));
        }
        currentUser.requireReachable(regionId, "Region not found");
        currentUser.requireIn(Privileges.CUSTOMER_MANAGE, regionId);
        return regionService.requireActive(regionId);
    }

    private static PaymentTerm settableTerm(PaymentTerm term) {
        if (term == PaymentTerm.CUSTOM) {
            throw new GlobalExceptionHandler.InvalidFieldsException(Map.of("paymentTerm",
                    "Custom terms belong on one invoice; pick one of " + PaymentTerm.SETTABLE));
        }
        return term;
    }

    /** The customer as its audit rows record it; also what a dispute on the customer shows before and after (§5.10.11). */
    public static Object snapshotOf(Customer c) {
        return new CustomerAuditSnapshot(c.getId(), c.getName(), c.getPhone(), c.getEmail(),
                c.getAddress(), c.getCreditBalance(), c.getPaymentTerm(), Region.idOf(c.getRegion()));
    }

    public record CustomerAuditSnapshot(Long id, String name, String phone, String email,
                                        String address, BigDecimal creditBalance,
                                        PaymentTerm paymentTerm, Long regionId) {}
}

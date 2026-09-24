package com.geneinvoice.customer;

import com.geneinvoice.approval.ApprovalDtos;
import com.geneinvoice.approval.ApprovalGate;
import com.geneinvoice.approval.ApprovalSchemas;
import com.geneinvoice.approval.PendingAction;
import com.geneinvoice.approval.PendingChangeCascade;
import com.geneinvoice.approval.PendingChangeRepository;
import com.geneinvoice.approval.PendingTargetType;
import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.Emails;
import com.geneinvoice.common.Passwords;
import com.geneinvoice.common.Strings;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.asof.AsOf;
import com.geneinvoice.common.asof.AsOfContext;
import com.geneinvoice.common.asof.AsOfSource;
import com.geneinvoice.common.query.Aggregates;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.PredicateFactory;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.common.GlobalExceptionHandler;
import com.geneinvoice.document.DocumentCascade;
import com.geneinvoice.history.HistoryDrift;
import com.geneinvoice.history.HistorySchemas;
import com.geneinvoice.email.EmailCascade;
import com.geneinvoice.invoice.InvoiceDates;
import com.geneinvoice.invoice.InvoiceHistoryRepository;
import com.geneinvoice.invoice.InvoiceProperties;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.invoice.PaymentTerm;
import com.geneinvoice.poc.CustomerPoc;
import com.geneinvoice.poc.CustomerPocHistory;
import com.geneinvoice.poc.CustomerPocHistoryRepository;
import com.geneinvoice.poc.CustomerPocRepository;
import com.geneinvoice.poc.PocDtos;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.region.CustomerRegionHistory;
import com.geneinvoice.region.CustomerRegionHistoryRepository;
import com.geneinvoice.region.Region;
import com.geneinvoice.region.RegionAccess;
import com.geneinvoice.region.RegionGrants;
import com.geneinvoice.region.RegionPlacements;
import com.geneinvoice.region.RegionRepository;
import com.geneinvoice.region.RegionRight;
import com.geneinvoice.region.RegionScope;
import com.geneinvoice.task.TaskCascade;
import com.geneinvoice.role.Role;
import com.geneinvoice.role.RoleRepository;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CustomerService {

    public static final String ENTITY = "CUSTOMER";

    private final CustomerRepository repository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final InvoiceRepository invoiceRepository;
    private final CustomerPocRepository customerPocRepository;
    // The seat mirror: who sat on the account on the date asked about. A vacated seat left
    // no live row behind it, so the live table cannot answer that question at all (B3).
    private final CustomerPocHistoryRepository customerPocHistoryRepository;
    // What the account owed and what was overdue THEN. The live sums read invoices; on a
    // past page they have to read the versions of those invoices that were in force then,
    // or the figure a row RENDERS disagrees with the figure the outstanding column SORTS
    // by — and that column is already as-of aware (B3).
    private final InvoiceHistoryRepository invoiceHistoryRepository;
    private final RegionRepository regionRepository;
    // The opening placement is written here, in the same transaction as the row it describes, so
    // customers.region_id and the ledger are never one without the other (B1).
    private final CustomerRegionHistoryRepository customerRegionHistoryRepository;
    private final RegionAccess regionAccess;
    // Above this branch's limit the save does not happen at all: the gate throws, this whole
    // transaction rolls back and the request is answered 202 with the change that is waiting (B2).
    private final ApprovalGate approvalGate;
    private final PasswordEncoder passwordEncoder;
    private final ScopeResolver scopeResolver;
    private final TableQueryExecutor queryExecutor;
    private final AuditService auditService;
    private final CurrentUser currentUser;
    private final InvoiceProperties invoiceProperties;
    private final DocumentCascade documentCascade;
    private final EmailCascade emailCascade;
    // Deleting an account takes the work outstanding on it with it. The cascade and not
    // TaskService: this is the DocumentCascade shape, and a service here would be a cycle the
    // moment TaskService reads a customer (A6).
    private final TaskCascade taskCascade;
    // Deleting an account closes every change still waiting on it, the documentCascade shape.
    // Not ApprovalService: that one reaches the applier, which reaches back here, and a cycle
    // Spring would refuse to build (B2).
    private final PendingChangeCascade pendingChangeCascade;
    // Which accounts have a change waiting on them. The repository and not ApprovalService: one
    // query for a whole page, joining the batch toDtos already runs, and the other direction
    // would be a Spring cycle because ApprovalService replays this service (B2).
    private final PendingChangeRepository pendingChangeRepository;
    // The region chips this list says it is narrowed by; empty for an unregioned table,
    // for a wildcard holder and for a customer login, so it is passed unconditionally (B1).
    private final RegionScope regionScope;
    // No mirror carries region_id, so an as-of row's branch comes from R7's placement
    // ledger. One query for the page, and not one statement on the live path (B3, B1).
    private final RegionPlacements regionPlacements;
    // Says so when a row in this answer was repaired by the reconciler rather than watched
    // happen. A no-op on the live path (B3).
    private final HistoryDrift historyDrift;

    /**
     * WHERE AN ACCOUNT LIST READS FROM (B3). The InvoiceService.invoiceSource() shape exactly,
     * and the whole of this class's as-of "routing": live it is the customers table, its live
     * schema and the POC book; under {@code ?asOf} it is the interval mirror, its as-of twin, and
     * the SAME book — because B3-SCHEMAS made the two seat subqueries root-agnostic.
     *
     * <p>{@code AsOf.at(T)} LEADS THE SCOPE LIST and is the line that makes the list count
     * ACCOUNTS. The twin carries the interval clause only inside its correlated subqueries (the
     * outstanding sum, the two seats, the region ledger); nothing filters the ROOT, so without
     * this predicate a page over customer_history would answer with every VERSION of every
     * account and totalElements would count edits.
     *
     * <p>IT ADDS NO REGION PREDICATE. The region axis is injected once by
     * TableQueryExecutor.predicates() for every root and is already as-of correct, because
     * B3-CONTEXT pointed RegionScope.effectiveAsOf at AsOfContext.date() (B1, B3).
     *
     * <p>The fetch list is empty on both sides: this list has never fetched an association, and a
     * mirror has none worth fetching anyway.
     */
    public AsOfSource<CustomerView> customerSource() {
        ScopeResolver.Scope book = scopeResolver.forCustomers();
        if (!AsOfContext.isActive()) {
            return new AsOfSource<>(Customer.class, TableSchemas.CUSTOMERS, book.predicates(),
                    book.lockedFilters(), List.of());
        }
        List<PredicateFactory> scope = new ArrayList<>();
        scope.add(AsOf.at(AsOfContext.instant()));
        scope.addAll(book.predicates());
        List<String> locked = new ArrayList<>(book.lockedFilters());
        locked.add("asOf:eq:" + AsOfContext.date());
        return new AsOfSource<>(CustomerHistory.class, HistorySchemas.CUSTOMERS,
                List.copyOf(scope), List.copyOf(locked), List.of());
    }

    /**
     * The branch an as-of row cannot answer for itself. customer_history mirrors customers.region_id
     * as an ordinary column, but that column is explicitly NOT the region axis (blueprint conflict
     * 1), so the two {@code @Transient} slots are filled from R7's placement ledger before the DTO
     * factory reads them — one query for the page, and not one statement on the live path (B3, B1).
     */
    private void placeRegions(List<? extends CustomerView> rows) {
        if (!AsOfContext.isActive()) return;
        List<CustomerHistory> mirrors = rows.stream()
                .filter(CustomerHistory.class::isInstance).map(CustomerHistory.class::cast).toList();
        if (mirrors.isEmpty()) return;
        Map<Long, RegionPlacements.Placement> placements = regionPlacements.at(
                mirrors.stream().map(CustomerHistory::getId).filter(Objects::nonNull).toList(),
                AsOfContext.date());
        for (CustomerHistory row : mirrors) {
            RegionPlacements.Placement placed = placements.get(row.getId());
            // A setter on a @Transient field does NOT dirty the managed row, which is why these
            // two are transient: writing a persisted field here would be flushed and would rewrite
            // history in order to answer a question about it (B3).
            row.setRegionId(placed == null ? null : placed.regionId());
            row.setRegionName(placed == null ? null : placed.regionName());
        }
    }

    /**
     * Downgrade this answer if the account mirror holds a row the reconciler repaired rather than
     * watched happen. The guard is on {@code isActive()} and not inside markIfDrifted, because
     * {@code AsOfContext.instant()} THROWS when nothing is open — evaluating the argument is
     * already too late (B3).
     */
    private void markDrift() {
        if (!AsOfContext.isActive()) return;
        historyDrift.markIfDrifted(CustomerHistory.class, AsOfContext.instant());
    }

    @Transactional(readOnly = true)
    public PageResponse<CustomerDtos.CustomerDto> page(TableQuery query) {
        AsOfSource<CustomerView> source = customerSource();
        var page = queryExecutor.run(source.type(), source.schema(), query,
                source.scope(), source.fetch());
        placeRegions(page.content());
        markDrift();
        return PageResponse.of(toDtos(page.content()), query, page.total(), source.locked(),
                regionScope.lockedFilters(source.type()));
    }

    @Transactional(readOnly = true)
    public List<Long> idsMatching(TableQuery query, int limit) {
        AsOfSource<CustomerView> source = customerSource();
        return queryExecutor.ids(source.type(), source.schema(), query, source.scope(), limit);
    }

    /**
     * The rows behind an export. {@code ? extends CustomerView} and not {@code Customer}, because
     * under {@code ?asOf} these are mirror rows — and the caller renders them through the view,
     * which is the whole reason the interface exists (B3).
     */
    @Transactional(readOnly = true)
    public List<? extends CustomerView> allMatching(TableQuery query) {
        AsOfSource<CustomerView> source = customerSource();
        List<? extends CustomerView> rows = queryExecutor.run(source.type(), source.schema(),
                query, source.scope(), source.fetch()).content();
        placeRegions(rows);
        markDrift();
        return rows;
    }

    /**
     * ONE ACCOUNT, LIVE OR AS OF A DATE — and 404, never 403, when it did not exist then (B3).
     *
     * <p>The InvoiceService.detail shape: the same mirror, through the same executor, with the
     * same scope list and an {@code id:eq:} filter and nothing else added, so the book, the region
     * axis and a customer login's own-account pin all come from one place rather than from a
     * second copy of {@link #get}'s checks.
     */
    @Transactional(readOnly = true)
    public CustomerDtos.CustomerDto detail(Long id) {
        if (!AsOfContext.isActive()) {
            return toDto(get(id));
        }
        requireOwnAccount(id);
        AsOfSource<CustomerView> source = customerSource();
        List<? extends CustomerView> rows = queryExecutor.run(source.type(), source.schema(),
                TableQuery.parseUnpaged(source.schema(), null, List.of("id:eq:" + id)),
                source.scope(), source.fetch()).content();
        if (rows.isEmpty()) {
            throw new NotFoundException("Customer not found");
        }
        placeRegions(rows);
        markDrift();
        return toDtos(rows).get(0);
    }

    /**
     * The visibility gate a read that is NOT a customer DTO still has to pass — today
     * GET /api/customers/{id}/pocs, whose answer is about the account and not about the row. Live
     * it is {@link #get}; as of a date it is the same question asked of the mirror, so an account
     * that did not exist then is 404 rather than a list of today's seats (B3, AUTH-08).
     */
    @Transactional(readOnly = true)
    public void requireVisible(Long id) {
        visibleAccount(id);
    }

    /**
     * The account itself as the reader may see it — live, or as it stood on the date asked about.
     *
     * <p>The one caller that is not a customer screen is GET /api/payments/credits/{customerId},
     * whose whole answer is a name and a credit balance: under {@code ?asOf} both of those are
     * that date's, and an account that did not exist then is 404 rather than a zero balance beside
     * a January page. Returns the VIEW, because the live root and the mirror root are different
     * types and every figure this answers is declared on the interface (B3).
     */
    @Transactional(readOnly = true)
    public CustomerView visibleAccount(Long id) {
        requireOwnAccount(id);
        if (!AsOfContext.isActive()) {
            Customer c = repository.findById(id)
                    .orElseThrow(() -> new NotFoundException("Customer not found"));
            requireInBook(id);
            return c;
        }
        AsOfSource<CustomerView> source = customerSource();
        List<? extends CustomerView> rows = queryExecutor.run(source.type(), source.schema(),
                TableQuery.parseUnpaged(source.schema(), null, List.of("id:eq:" + id)),
                source.scope(), source.fetch()).content();
        if (rows.isEmpty()) {
            throw new NotFoundException("Customer not found");
        }
        placeRegions(rows);
        markDrift();
        return rows.get(0);
    }

    /** A customer login may ask about its own account and about no other, on any date (AUTH-02). */
    private void requireOwnAccount(Long id) {
        Long callerCustomer = currentUser.customerIdOrNull();
        if (callerCustomer != null && !callerCustomer.equals(id)) {
            throw new AccessDeniedException("Not allowed");
        }
    }

    @Transactional(readOnly = true)
    public CustomerDtos.CustomerSummaryTiles tiles(TableQuery query) {
        AsOfSource<CustomerView> source = customerSource();
        markDrift();
        // The six selection lambdas are UNCHANGED: creditBalance, the approval EXISTS and the
        // count all name an attribute the mirror spells the same way and types the same way. The
        // two subqueries below are the only part that had to move, because they root on a DIFFERENT
        // table — invoices and customer_pocs — and those two have mirrors of their own (B3).
        Object[] row = queryExecutor.aggregate(source.type(), source.schema(), query,
                source.scope(), (root, q, cb) -> List.of(
                        cb.count(root.get("id")),
                        cb.coalesce(cb.sum(outstandingSubquery(root, q, cb)), BigDecimal.ZERO),
                        cb.coalesce(cb.sum(root.<BigDecimal>get("creditBalance")), BigDecimal.ZERO),
                        Aggregates.countWhen(cb, cb.not(cb.exists(
                                seatSubquery(root, q, cb, PocType.SUCCESS)))),
                        Aggregates.countWhen(cb, cb.not(cb.exists(
                                seatSubquery(root, q, cb, PocType.COLLECTION)))),
                        // Inside the SAME aggregate, so the tile costs no extra round trip and
                        // can never disagree with the approvalPending filter chip (B2).
                        Aggregates.countWhen(cb, ApprovalSchemas.existsOpenPending(
                                root, q, cb, PendingTargetType.CUSTOMER))));
        return new CustomerDtos.CustomerSummaryTiles(
                Aggregates.asLong(row[0]), Aggregates.asMoney(row[1]), Aggregates.asMoney(row[2]),
                Aggregates.asLong(row[3]), Aggregates.asLong(row[4]), Aggregates.asLong(row[5]));
    }

    /**
     * What the accounts on this page owed, as ONE correlated sum inside the tile aggregate.
     *
     * <p>The body moved to {@code TableSchemas.customerOutstanding}, which B3-SCHEMAS generalised
     * over the invoice root for exactly this: the tile and the sortable {@code outstanding} column
     * are now the SAME expression built by the same method, so a January tile and a January sort
     * cannot disagree about what January's balances were (B3).
     */
    private jakarta.persistence.criteria.Expression<BigDecimal> outstandingSubquery(
            jakarta.persistence.criteria.Root<?> root,
            jakarta.persistence.criteria.CriteriaQuery<?> q,
            jakarta.persistence.criteria.CriteriaBuilder cb) {
        boolean asOf = AsOfContext.isActive();
        // The root type in a local of its own, for the reason seatSubquery below states (B3).
        Class<?> invoiceType = asOf
                ? com.geneinvoice.invoice.InvoiceHistory.class
                : com.geneinvoice.invoice.Invoice.class;
        return TableSchemas.customerOutstanding(invoiceType,
                asOf ? AsOf.at(AsOfContext.instant()) : null, root, q, cb);
    }

    /**
     * Whether anybody sat in this seat — today, or on the date asked about (B3).
     *
     * <p>A THIRD SPELLING of the seat switch, and it is named as one: ScopeResolver's
     * customerHasPocSeat holds the book's copy and TableSchemas.pocSeatPredicate holds the filter
     * column's, and neither of them takes a bare Customer root with no FilterSpec, which is what a
     * tile aggregate hands in. If the seat mirror's shape ever changes, all three move together.
     */
    private Subquery<Long> seatSubquery(jakarta.persistence.criteria.Root<?> root,
                                        jakarta.persistence.criteria.CriteriaQuery<?> q,
                                        jakarta.persistence.criteria.CriteriaBuilder cb,
                                        PocType type) {
        boolean asOf = AsOfContext.isActive();
        // The root type in a local of its own and never inside the from(...) call: a conditional
        // expression whose two arms are different Class literals widens to a type javac accepts
        // there and ECJ does not, and this build has already lost an afternoon to an
        // "Unresolved compilation problem" in a class file nobody wrote by hand. TableSchemas'
        // own generalised helpers take a Class<?> parameter for the same reason (B3).
        Class<?> seatType = asOf ? CustomerPocHistory.class : CustomerPoc.class;
        Subquery<Long> sq = q.subquery(Long.class);
        // CustomerPoc walks an association to its account and CustomerPocHistory carries a flat
        // customerId, because a mirror never walks to a live row of a mirrored entity (B3).
        jakarta.persistence.criteria.Root<?> seat = sq.from(seatType);
        List<Predicate> where = new ArrayList<>();
        jakarta.persistence.criteria.Expression<Long> seatCustomerId = asOf
                ? seat.get("customerId")
                : seat.get("customer").get("id");
        where.add(cb.equal(seatCustomerId, root.get("id")));
        where.add(cb.equal(seat.get("pocType"), type));
        if (asOf) where.add(AsOf.at(AsOfContext.instant()).build(seat, q, cb));
        sq.select(cb.literal(1L)).where(where.toArray(new Predicate[0]));
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

    private void requireInBook(Long id) {
        if (!queryExecutor.inScope(Customer.class, TableSchemas.CUSTOMERS, id,
                scopeResolver.forCustomers().predicates())) {
            throw new NotFoundException("Customer not found");
        }
    }

    @Transactional(readOnly = true)
    public CustomerDtos.CustomerDto toDto(CustomerView c) {
        return toDtos(List.of(c)).get(0);
    }

    /**
     * The parameter is the VIEW and not the entity, so ONE mapper serves the live row and the as-of
     * mirror row and the two cannot drift apart. Source-compatible — Customer implements
     * CustomerView, so no call site moves. Everything a customer row carries that is NOT on the
     * customer row is already batched by ids below and never read off the entity, which is why the
     * widening costs nothing here (B3).
     */
    @Transactional(readOnly = true)
    public List<CustomerDtos.CustomerDto> toDtos(List<? extends CustomerView> customers) {
        if (customers.isEmpty()) return List.of();
        boolean showPoc = scopeResolver.canSeePoc();
        // WHICH BRANCH an account is filed in is internal: a customer login reading its OWN
        // account gets B1's two slots empty, the same two TableSchema.visibleTo drops from their
        // column list, so the payload and the schema agree (B1, AUTH-08).
        boolean showRegion = scopeResolver.canSeeRegion();
        List<Long> ids = customers.stream().map(CustomerView::getId).toList();

        // The two money figures on an account row are sums over its INVOICES, so on a past page
        // they have to be sums over the versions of those invoices that were in force then. Same
        // two queries, same grouping, same coalesce; the flat customerId in place of the
        // association walk and the interval clause added. InvoiceDates.today() is already the
        // as-of date, so the ageing in the overdue sum is that date's ageing too (B3).
        boolean asOf = AsOfContext.isActive();
        Instant at = asOf ? AsOfContext.instant() : null;
        Map<Long, BigDecimal> outstanding = new HashMap<>();
        for (Object[] row : asOf
                ? invoiceHistoryRepository.sumOutstandingByCustomerAsOf(ids, at)
                : invoiceRepository.sumOutstandingByCustomer(ids)) {
            outstanding.put((Long) row[0], Aggregates.asMoney(row[1]));
        }

        Map<Long, BigDecimal> overdue = new HashMap<>();
        for (Object[] row : asOf
                ? invoiceHistoryRepository.sumOverdueByCustomerAsOf(ids, InvoiceDates.today(), at)
                : invoiceRepository.sumOverdueByCustomer(ids, InvoiceDates.today())) {
            overdue.put((Long) row[0], Aggregates.asMoney(row[1]));
        }

        Map<Long, String> usernames = new HashMap<>();
        for (Long id : ids) {
            userRepository.findByCustomerId(id).ifPresent(u -> usernames.put(id, u.getUsername()));
        }

        // Joins the batch this method already runs: the page's flags are one more query, not one
        // per row, and none at all when there is nothing on the page (B2).
        Set<Long> held = pendingChangeRepository.openTargetIds(PendingTargetType.CUSTOMER, ids);

        // The seats THEN, on a past page: a seat vacated in February was still held in January and
        // the live table has no row left to say so. Mapped to the DTO here rather than kept as
        // entities, because the two roots are different types and only one of them is a
        // CustomerPoc — PocDtos.CustomerPocDto.from's two overloads are the one mapping (B3).
        Map<Long, List<PocDtos.CustomerPocDto>> seats = new LinkedHashMap<>();
        if (showPoc && asOf) {
            for (var seat : customerPocHistoryRepository.inForce(ids, at)) {
                seats.computeIfAbsent(seat.getCustomerId(), k -> new ArrayList<>())
                        .add(PocDtos.CustomerPocDto.from(seat));
            }
        } else if (showPoc) {
            for (CustomerPoc seat : customerPocRepository.findByCustomerIdIn(ids)) {
                seats.computeIfAbsent(seat.getCustomer().getId(), k -> new ArrayList<>())
                        .add(PocDtos.CustomerPocDto.from(seat));
            }
        }

        return customers.stream().map(c -> {
            List<PocDtos.CustomerPocDto> mine = seats.getOrDefault(c.getId(), List.of());
            List<PocDtos.CustomerPocDto> success = mine.stream()
                    .filter(s -> s.pocType() == PocType.SUCCESS).toList();
            List<PocDtos.CustomerPocDto> collection = mine.stream()
                    .filter(s -> s.pocType() == PocType.COLLECTION).toList();
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
                    c.getRegionId(), c.getRegionName(),
                    held.contains(c.getId()));
        }).map(row -> showRegion ? row : row.withoutRegion()).toList();
    }

    // not gated (B2): creditBalance appears on no request record, so opening an account moves no
    // money. The only writer of Customer.creditBalance is CreditLedger, under a gated caller.
    @Transactional
    public Customer create(CustomerDtos.CustomerCreateRequest in) {
        // Where this account is opened, resolved before anything is written and before anything
        // else is even examined: a caller who may not work in the branch they named is refused
        // plainly rather than after being told which usernames are taken (B1).
        Region region = openingRegion(in.regionId());
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

        Customer c = repository.save(Customer.builder()
                // Trimmed, and a box the user left empty is stored as nothing rather than as an
                // empty string, so the list shows its "—" placeholder (CP-15).
                .name(Strings.trim(in.name())).phone(Strings.blankToNull(in.phone()))
                .email(email).address(Strings.blankToNull(in.address()))
                .paymentTerm(settableTerm(in.paymentTerm()))
                // customers.region_id is NOT NULL from RegionSchemaUpgrade onwards, so an account
                // has to be placed the moment it is created (B1).
                .region(region)
                .build());

        // The opening placement, in the same transaction as the row it describes and dated with the
        // wall clock rather than with any date a reader asked for (B1, B3).
        customerRegionHistoryRepository.save(CustomerRegionHistory.builder()
                .customerId(c.getId()).regionId(region.getId())
                .validFrom(InvoiceDates.todayForWrite())
                .movedByUserId(currentUser.idOrNull())
                .reason("Account opened in " + region.getCode())
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
                .build());

        auditService.record(ENTITY, c.getId(), "CUSTOMER_CREATED", null, snapshot(c),
                currentUser.require().getId(), null, null);
        return c;
    }

    /**
     * Where a new account is opened. A region the caller NAMED is checked at MANAGE and refused
     * with 403 rather than 404, because no id space is being probed (B1, D-46). Naming nothing is
     * answered only when there is exactly one answer: guessing a branch for somebody who works in
     * three would file accounts in the wrong one silently, and the caller is asked instead (B1).
     */
    private Region openingRegion(Long regionId) {
        if (regionId != null) {
            Region named = regionRepository.findById(regionId)
                    .filter(Region::isActive)
                    .orElseThrow(() -> new BadRequestException("Unknown or inactive region"));
            regionAccess.requireManage(named.getId());
            return named;
        }
        List<Region> mine = manageableRegions();
        if (mine.size() != 1) {
            throw new GlobalExceptionHandler.InvalidFieldsException(
                    Map.of("regionId", "Choose a region"));
        }
        return mine.get(0);
    }

    /**
     * The branches this caller could open an account in. A wildcard holder may open one anywhere
     * there is, so "their only region" is the company's only region — which is why an installation
     * with one branch behaves exactly as it did before regions existed, and the day a second branch
     * opens the administrator is asked which one they meant (B1).
     */
    private List<Region> manageableRegions() {
        RegionGrants grants = currentUser.grants();
        List<Region> candidates = grants.allRegions(RegionRight.MANAGE)
                ? regionRepository.findAll()
                : regionRepository.findAllById(grants.with(RegionRight.MANAGE));
        return candidates.stream().filter(Region::isActive).toList();
    }

    /**
     * The ONE way customers.region_id is written after an account exists, and it exists because
     * Customer.setRegion is package-private: RegionCustodyService lives in com.geneinvoice.region
     * and cannot reach a customer-package setter, while widening that setter would let anything at
     * all move an account without writing the customer_region_history row that has to agree with
     * it. RegionCustodyService.move is the only caller, and it holds the customer row lock and
     * writes the placement row in the same transaction (B1).
     */
    @Transactional
    public Customer placeIn(Customer customer, Region region) {
        customer.setRegion(region);
        return repository.save(customer);
    }

    // not gated (B2), for the same reason create is not: CustomerUpdateRequest carries no
    // monetary field and the credit balance is engine-derived. Deleting an account IS gated, and
    // always, because that one cannot be undone.
    @Transactional
    public Customer update(Long id, CustomerDtos.CustomerUpdateRequest in) {
        Customer c = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Customer not found"));
        requireInBook(id);
        // Reaching the account is a read and is already answered by requireInBook with 404;
        // CHANGING it needs MANAGE in the branch it is filed in, so a view-only grant reads this
        // customer and is refused when it tries to edit it. Moving it between branches is a
        // different act with its own two checks, in RegionCustodyService (B1, AUTH-08, D-46).
        regionAccess.requireManage(c.getRegion().getId());
        Object before = snapshot(c);
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
                linked.setCredentialsChangedAt(java.time.Instant.now());
            }
            userRepository.save(linked);
        }
        auditService.record(ENTITY, id, "CUSTOMER_UPDATED", before, snapshot(saved),
                currentUser.require().getId(), null, null);
        if (previousTerm != saved.getPaymentTerm()) {
            auditService.record(ENTITY, id, "CUSTOMER_PAYMENT_TERM_CHANGED",
                    previousTerm == null ? null : previousTerm.name(),
                    saved.getPaymentTerm() == null ? null : saved.getPaymentTerm().name(),
                    currentUser.require().getId(), null, null);
        }
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        requireInBook(id);
        Customer c = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Customer not found"));
        // The most destructive write on this entity, and it needs the same MANAGE in the account's
        // own branch that editing it does (B1).
        regionAccess.requireManage(c.getRegion().getId());
        // Always checked, whatever the balance: this takes the login, the POC seats and every
        // document on the account and on its invoices and payments with it, and none of that can
        // be put back. A threshold on the credit balance would let the emptiest account — the one
        // most likely to be deleted by mistake — through unseen (CP-04, B2).
        approvalGate.check(ApprovalGate.Proposal.on(
                PendingAction.CUSTOMER_DELETE, id, id, c.getCreditBalance(),
                new ApprovalDtos.NoPayload(), snapshot(c), c.getVersion(),
                "Delete " + c.getName()));
        // What is about to go, read before the cascade takes it (CP-04).
        Object before = snapshot(c);
        // Children before parents, and this one first: a change still waiting on an account that
        // is about to stop existing can never be approved and can never be recognised by whoever
        // would reject it, so it is closed here with a reason rather than left in the queue for
        // ever. The change being applied right now — this deletion's own — is left alone (B2).
        pendingChangeCascade.onCustomerDeleted(id);
        documentCascade.onCustomerDeleted(id);
        // The emails recorded against it stay — they are a record of something that was said —
        // but stop claiming the customer is still there to link to (CP-13).
        emailCascade.onCustomerDeleted(id);
        // Tasks go outright, unlike emails: an email records something that was said, a task
        // records work owed on a record that is about to stop existing (A6, CP-13).
        taskCascade.onCustomerDeleted(id);
        for (CustomerPoc seat : customerPocRepository.findByCustomerIdOrderByPocTypeAscPrimaryDescIdAsc(id)) {
            customerPocRepository.delete(seat);
        }
        userRepository.findByCustomerId(id).ifPresent(userRepository::delete);
        // The placement ledger goes with the account. customer_region_history.customer_id is a
        // plain Long with no @ManyToOne, so no foreign key does this for us and the delete below
        // would succeed over the top of it, leaving an OPEN row — one still saying in the present
        // tense that this account is in that branch — pointing at a customer that has gone. It is
        // the only child of customers that neither a cascade nor the database was cleaning up
        // (B1, CP-04).
        customerRegionHistoryRepository.deleteByCustomerId(id);
        repository.deleteById(id);
        // Removing a customer takes its login, its POC seats and every document on it and on its
        // invoices and payments with it. It is the most destructive write on this entity and was
        // the only one leaving no trail at all (CP-04).
        auditService.record(ENTITY, id, "CUSTOMER_DELETED", before, null,
                currentUser.require().getId(), null, "Customer deleted");
    }

    private static PaymentTerm settableTerm(PaymentTerm term) {
        if (term == PaymentTerm.CUSTOM) {
            throw new GlobalExceptionHandler.InvalidFieldsException(Map.of("paymentTerm",
                    "Custom terms belong on one invoice; pick one of " + PaymentTerm.SETTABLE));
        }
        return term;
    }

    private Object snapshot(Customer c) {
        return new CustomerAuditSnapshot(c.getId(), c.getName(), c.getPhone(), c.getEmail(),
                c.getAddress(), c.getCreditBalance(), c.getPaymentTerm());
    }

    public record CustomerAuditSnapshot(Long id, String name, String phone, String email,
                                        String address, BigDecimal creditBalance,
                                        PaymentTerm paymentTerm) {}
}

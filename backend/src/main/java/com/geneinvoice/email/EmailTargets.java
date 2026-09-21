package com.geneinvoice.email;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.Emails;
import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.bulk.BulkDtos;
import com.geneinvoice.common.query.PredicateFactory;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchema;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.dispute.DisputeRepository;
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.dispute.DisputeStatus;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.email.EmailDtos.EmailToken;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentRepository;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.product.Product;
import com.geneinvoice.product.ProductRepository;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.promise.PaymentPromiseRepository;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.role.Role;
import com.geneinvoice.role.RoleRepository;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.geneinvoice.email.EmailEntityType.*;
import static com.geneinvoice.email.EmailRole.COLLECTION_POC;
import static com.geneinvoice.email.EmailRole.CUSTOMER_SUCCESS_POC;
import static com.geneinvoice.email.EmailRole.SALES_POC;

/**
 * Everything the email feature knows about the records an email can be about, kept in one place
 * (§4): the privilege that lets a caller see each kind, how one is loaded under the caller's
 * customer restriction and POC book, what it is called and where it opens, which customer it
 * belongs to, who holds each role on it at each level, how a bulk selection of it is resolved, and
 * what an email about a new or changed one might say.
 */
@Component
@RequiredArgsConstructor
public class EmailTargets {

    /** The privilege that lets a caller see each kind of record, and so write or read email about it. */
    private static final Map<EmailEntityType, String> VIEW_PRIVILEGE = Map.of(
            CUSTOMER, Privileges.CUSTOMER_VIEW,
            INVOICE, Privileges.INVOICE_VIEW,
            PAYMENT, Privileges.PAYMENT_VIEW,
            PROMISE, Privileges.PROMISE_VIEW,
            DISPUTE, Privileges.DISPUTE_VIEW,
            PRODUCT, Privileges.PRODUCT_VIEW,
            USER, Privileges.USER_VIEW,
            ROLE, Privileges.ROLE_VIEW);

    /** Of those, the kinds a customer login may ever use — and only their own rows. */
    private static final Set<EmailEntityType> CUSTOMER_READABLE = EnumSet.of(CUSTOMER, INVOICE, PAYMENT, PROMISE, DISPUTE);

    /** Kinds that never belong to a customer, so "customer emails" cannot apply to them. */
    private static final Set<EmailEntityType> WITHOUT_CUSTOMER = EnumSet.of(PRODUCT, ROLE);

    /**
     * The seats a customer can actually hold, in the order the compose form shows them, which is
     * what the customer level answers for (L2). The Sales POC is not one of them: it is assigned per invoice, and
     * {@code PocService.add} refuses a customer-level SALES seat outright, so offering the role
     * here only ever produced a recipient nobody holds — a dead entry in the compose form, a
     * sender the form then refuses to send from, and a bulk send that skipped every row (CP-01).
     */
    private static final List<EmailRole> CUSTOMER_BOOK = List.of(CUSTOMER_SUCCESS_POC, COLLECTION_POC);

    /**
     * The roles the customer's POC book answers for, wherever a record has a customer (L2). A
     * product or a role belongs to nobody, so neither group applies to them.
     */
    private static final Map<EmailEntityType, List<EmailRole>> CUSTOMER_ROLES = Map.of(
            CUSTOMER, CUSTOMER_BOOK,
            INVOICE, CUSTOMER_BOOK,
            PAYMENT, CUSTOMER_BOOK,
            PROMISE, CUSTOMER_BOOK,
            DISPUTE, CUSTOMER_BOOK,
            PRODUCT, List.of(),
            USER, CUSTOMER_BOOK,
            ROLE, List.of());

    /**
     * The POC fields a record of each kind stores, one person each (L3). A dispute's are its
     * target's, so it can store either and offers both, one of them always unheld (L4).
     */
    private static final Map<EmailEntityType, List<EmailRole>> RECORD_ROLES = Map.of(
            CUSTOMER, List.of(),
            INVOICE, List.of(SALES_POC),
            PAYMENT, List.of(COLLECTION_POC),
            PROMISE, List.of(COLLECTION_POC),
            DISPUTE, List.of(SALES_POC, COLLECTION_POC),
            PRODUCT, List.of(),
            USER, List.of(),
            ROLE, List.of());

    /** Both groups, customer level first, each in the order of {@link #CUSTOMER_BOOK} (L4). */
    private static final Map<EmailEntityType, List<RoleRef>> ROLES_OFFERED =
            Arrays.stream(EmailEntityType.values()).collect(Collectors.toUnmodifiableMap(type -> type,
                    type -> Stream.concat(
                            CUSTOMER_ROLES.get(type).stream().map(RoleRef::customer),
                            RECORD_ROLES.get(type).stream().map(RoleRef::record)).toList()));

    private final CustomerService customerService;
    private final InvoiceService invoiceService;
    private final PaymentService paymentService;
    private final PaymentPromiseService promiseService;
    private final DisputeService disputeService;
    private final CustomerRepository customerRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentPromiseRepository promiseRepository;
    private final DisputeRepository disputeRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PocService pocService;
    private final ScopeResolver scopeResolver;
    private final TableQueryExecutor queryExecutor;
    private final EmailDirectory directory;
    private final CurrentUser currentUser;

    /** Someone an email can come from or go to, as they were when it was sent. */
    public record Person(Long userId, String name, String address, Long customerId, boolean internal) {
        static Person of(User u) {
            return new Person(u.getId(), EmailText.nameOf(u), Emails.normalize(u.getEmail()),
                    u.getCustomerId(), u.getCustomerId() == null);
        }
    }

    /**
     * A record as an email about it needs it. Role holders and the customer's addresses are looked up
     * when this is built, which is when the email is sent (E4). {@code holders} has an entry only for
     * a (role, level) someone active holds, and never an empty list.
     */
    public record Target(EmailEntityType type, Long id, String label, Long customerId,
                         Map<RoleRef, List<Person>> holders, List<Person> customerEmails) {

        public String link() {
            return EmailTargets.link(type, id);
        }

        /**
         * Everyone the role reaches in To at that level: every active seat holder at customer level,
         * the one person the record stores at record level (L2, L3). Empty when nobody holds it.
         */
        public List<Person> holders(RoleRef role) {
            return holders.getOrDefault(role, List.of());
        }

        /** Who sends when the role is the From: an email has one sender, so the first holder (L5). */
        public Optional<Person> sender(RoleRef role) {
            return holders(role).stream().findFirst();
        }
    }

    /** What happened to the record, for a suggested email. */
    public enum Event {
        CREATED, UPDATED;

        public static Event parse(String raw) {
            String wanted = raw == null ? "" : raw.trim();
            for (Event e : values()) {
                if (e.name().equalsIgnoreCase(wanted)) return e;
            }
            throw new BadRequestException("event must be one of " + Arrays.toString(values()));
        }
    }

    // ---- facts about each kind -------------------------------------------------------

    public static String link(EmailEntityType type, Long id) {
        String base = switch (type) {
            case CUSTOMER -> "/customers";
            case INVOICE -> "/invoices";
            case PRODUCT -> "/products";
            case PAYMENT -> "/payments";
            case PROMISE -> "/promises";
            case DISPUTE -> "/disputes";
            case USER -> "/users";
            case ROLE -> "/roles";
        };
        return base + "/" + id;
    }

    /**
     * The (role, level) pairs offered on a kind of record, as a list or bulk compose offers them
     * before any record is known: its record-level roles are the union of what its records can
     * store (L3).
     */
    public List<RoleRef> rolesOffered(EmailEntityType type) {
        return ROLES_OFFERED.get(type);
    }

    /**
     * The pairs offered on this record. A user who is not a customer login belongs to no customer, so
     * no seat applies and nobody could ever hold the role; the kind still offers them, since a list of
     * users may hold customer logins (§4).
     */
    public List<RoleRef> rolesOffered(Target target) {
        if (target.type() == USER && target.customerId() == null) return List.of();
        return rolesOffered(target.type());
    }

    /**
     * The level a role token without one means (L7): the customer's book where the kind has it
     * there, else the record's own field. It is read against the kind, so a role a record cannot
     * have is still refused by its own name — "Sales POC (customer) is not a role on users".
     */
    public static RoleLevel defaultLevel(EmailEntityType type, EmailRole role) {
        return CUSTOMER_ROLES.get(type).contains(role) ? RoleLevel.CUSTOMER : RoleLevel.RECORD;
    }

    public boolean mayHaveCustomer(EmailEntityType type) {
        return !WITHOUT_CUSTOMER.contains(type);
    }

    // ---- access and loading ------------------------------------------------------------

    /**
     * The caller may use this kind of record at all: they hold its view privilege, and a customer
     * login only the kinds a customer can read.
     */
    public void requireTypeAccess(EmailEntityType type) {
        if (!currentUser.has(VIEW_PRIVILEGE.get(type))) {
            throw new AccessDeniedException("Not allowed");
        }
        if (currentUser.isCustomer() && !CUSTOMER_READABLE.contains(type)) {
            throw new AccessDeniedException("Not allowed");
        }
    }

    /**
     * The caller can see the record: the kind is open to them, and the record's own read by id
     * accepts them, which applies the customer restriction and the POC's book (403 / 404 as it throws).
     */
    @Transactional(readOnly = true)
    public void requireVisible(EmailEntityType type, Long id) {
        requireTypeAccess(type);
        loadScoped(type, id);
    }

    /** The record, checked as {@link #requireVisible}, with its role holders and customer addresses as of now. */
    @Transactional(readOnly = true)
    public Target load(EmailEntityType type, Long id) {
        requireTypeAccess(type);
        return describe(type, loadScoped(type, id));
    }

    /**
     * The same, for work that runs with nobody logged in — an automation rule's consumer, or a
     * scheduled run (A3). No privilege and no customer restriction is applied, because there is no
     * caller to apply them to, so this is only ever reached from a background thread acting on a
     * rule a person already wrote and was allowed to write. Anything serving a request must use
     * {@link #load}. Empty when the record has since gone.
     */
    @Transactional(readOnly = true)
    public Optional<Target> loadInBackground(EmailEntityType type, Long id) {
        return loadUnscoped(type, id).map(entity -> describe(type, entity));
    }

    /**
     * A whole page of records of one kind, keyed by id: the same answer {@link #loadInBackground}
     * gives for one, for many, at a cost that does not grow with the number of rows (A9). A record
     * that has since gone is simply absent, exactly as it is there.
     *
     * <p>Unscoped for the reason {@link #loadInBackground} is: a caller reaches this with rows the
     * read that produced them has already scoped, so re-loading each one under their own book both
     * re-asks a question already answered and turns one row outside that book into a failure that
     * would roll the whole page back. Anything that has <em>not</em> already scoped its rows must
     * use {@link #load} per record.
     *
     * <p>What it saves is the several queries a row used to cost: the records the rows point at are
     * read one table at a time, each customer's addresses once for the page, and each customer's
     * POC book once however many rows share it.
     */
    @Transactional(readOnly = true)
    public Map<Long, Target> loadAllInBackground(EmailEntityType type, Collection<Long> ids) {
        List<Long> wanted = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (wanted.isEmpty()) return Map.of();
        List<?> entities = loadAllUnscoped(type, wanted);
        Shared shared = new Shared();
        shared.readRecordsBehind(type, entities);
        List<Facts> facts = entities.stream().map(entity -> facts(type, entity, shared)).toList();
        shared.readCustomers(facts.stream().map(Facts::customerId).toList());
        Map<Long, Target> targets = new LinkedHashMap<>();
        for (Facts f : facts) {
            targets.put(f.id(), target(type, f, shared));
        }
        return targets;
    }

    private Optional<?> loadUnscoped(EmailEntityType type, Long id) {
        return switch (type) {
            case CUSTOMER -> customerRepository.findById(id);
            case INVOICE -> invoiceRepository.findById(id);
            case PAYMENT -> paymentRepository.findById(id);
            case PROMISE -> promiseRepository.findById(id);
            case DISPUTE -> disputeRepository.findById(id);
            case PRODUCT -> productRepository.findById(id);
            case USER -> userRepository.findById(id);
            case ROLE -> roleRepository.findById(id);
        };
    }

    /** The same by the handful, in one read of the table the kind lives in; ids no longer there are absent. */
    private List<?> loadAllUnscoped(EmailEntityType type, Collection<Long> ids) {
        return switch (type) {
            case CUSTOMER -> customerRepository.findAllById(ids);
            case INVOICE -> invoiceRepository.findAllById(ids);
            case PAYMENT -> paymentRepository.findAllById(ids);
            case PROMISE -> promiseRepository.findAllById(ids);
            case DISPUTE -> disputeRepository.findAllById(ids);
            case PRODUCT -> productRepository.findAllById(ids);
            case USER -> userRepository.findAllById(ids);
            case ROLE -> roleRepository.findAllById(ids);
        };
    }

    private Object loadScoped(EmailEntityType type, Long id) {
        return switch (type) {
            case CUSTOMER -> customerService.get(id);
            case INVOICE -> invoiceService.get(id);
            case PAYMENT -> paymentService.get(id);
            case PROMISE -> promiseService.get(id);
            case DISPUTE -> disputeService.get(id);
            // No customer restriction or book applies to these; the view privilege is the whole rule.
            case PRODUCT -> productRepository.findById(id)
                    .orElseThrow(() -> new NotFoundException("Product not found"));
            case USER -> userRepository.findById(id)
                    .orElseThrow(() -> new NotFoundException("User not found"));
            case ROLE -> roleRepository.findById(id)
                    .orElseThrow(() -> new NotFoundException("Role not found"));
        };
    }

    private Target describe(EmailEntityType type, Object entity) {
        Shared shared = new Shared();
        return target(type, facts(type, entity, shared), shared);
    }

    /** What one record says about itself, before anybody has been looked up against it. */
    private record Facts(Long id, String label, Long customerId,
                         Map<EmailRole, Optional<User>> ownFields) {}

    private Facts facts(EmailEntityType type, Object entity, Shared shared) {
        return switch (type) {
            case CUSTOMER -> {
                Customer c = (Customer) entity;
                yield new Facts(c.getId(), "Customer " + c.getName(), c.getId(), Map.of());
            }
            case INVOICE -> {
                Invoice i = (Invoice) entity;
                yield new Facts(i.getId(), "Invoice " + i.getInvoiceNumber(), i.getCustomer().getId(),
                        Map.of(SALES_POC, Optional.ofNullable(i.getSalesPoc())));
            }
            case PAYMENT -> {
                Payment p = (Payment) entity;
                yield new Facts(p.getId(), "Payment #" + p.getId(), p.getCustomer().getId(),
                        Map.of(COLLECTION_POC, Optional.ofNullable(p.getCollectionPoc())));
            }
            case PROMISE -> {
                PaymentPromise p = (PaymentPromise) entity;
                yield new Facts(p.getId(), "Promise #" + p.getId(), p.getCustomer().getId(),
                        Map.of(COLLECTION_POC, Optional.ofNullable(p.getCollectionPoc())));
            }
            case DISPUTE -> {
                Dispute d = (Dispute) entity;
                yield new Facts(d.getId(), "Dispute #" + d.getId(), d.getCustomerId(), shared.disputeFields(d));
            }
            case PRODUCT -> {
                Product p = (Product) entity;
                yield new Facts(p.getId(), "Product " + p.getName(), null, Map.of());
            }
            case USER -> {
                // Only a customer login belongs to a customer, and only then do its seats apply.
                User u = (User) entity;
                yield new Facts(u.getId(), "User " + u.getUsername(), u.getCustomerId(), Map.of());
            }
            case ROLE -> {
                Role r = (Role) entity;
                yield new Facts(r.getId(), "Role " + r.getName(), null, Map.of());
            }
        };
    }

    private Target target(EmailEntityType type, Facts facts, Shared shared) {
        return new Target(type, facts.id(), EmailText.fit(facts.label(), Email.LABEL_MAX), facts.customerId(),
                holders(type, facts.customerId(), facts.ownFields(), shared),
                shared.addressesOf(facts.customerId()));
    }

    /**
     * Who holds each (role, level) offered on the record: at customer level everyone active in that
     * seat on the customer — the primary first, so the first holder is also who new records default
     * to (L2) — and at record level the one person the record's own POC field names (L3). Inactive
     * people hold nothing: a record whose own POC is inactive leaves the role unresolved rather than
     * silently reassigned. The customer's book answers for all three seats, so {@link Shared} reads
     * it once per customer rather than once per role — or, on a page, once for every row that shares
     * that customer.
     */
    private Map<RoleRef, List<Person>> holders(EmailEntityType type, Long customerId,
                                               Map<EmailRole, Optional<User>> ownFields, Shared shared) {
        Map<RoleRef, List<Person>> holders = new LinkedHashMap<>();
        for (RoleRef ref : ROLES_OFFERED.get(type)) {
            List<User> people;
            if (ref.level() == RoleLevel.RECORD) {
                people = ownFields.getOrDefault(ref.role(), Optional.empty()).stream().toList();
            } else if (customerId == null) {
                people = List.of();
            } else {
                people = shared.seatsOf(customerId).getOrDefault(ref.role().pocType(), List.of());
            }
            List<Person> active = people.stream().filter(User::isActive).map(Person::of).toList();
            if (!active.isEmpty()) holders.put(ref, active);
        }
        return holders;
    }

    /**
     * The customer's own email and the email of each active login (which usually repeats it; the
     * recipient list merges the two). People without an address are left out, and a customer no
     * longer on file has none. Handed back unmodifiable because one page's rows about the same
     * customer are given the same list.
     */
    private static List<Person> emailsOf(Customer customer, List<User> logins) {
        List<Person> emails = new ArrayList<>();
        if (customer != null) {
            String address = Emails.normalize(customer.getEmail());
            if (address != null) {
                emails.add(new Person(null, customer.getName(), address, customer.getId(), false));
            }
        }
        for (User login : logins) {
            Person p = Person.of(login);
            if (p.address() != null) emails.add(p);
        }
        return List.copyOf(emails);
    }

    /**
     * What one rendering has already read, so that describing many records does not read it again
     * for every one of them (A9). Three of the reads behind a {@link Target} are shared between
     * rows and cost a query each: the customer's POC book, the customer's own addresses, and — for
     * a dispute, whose POC is the POC of the record it is about (L3) — that invoice or payment.
     *
     * <p>One of these belongs to a single rendering and goes with it, so nothing it remembers can
     * be stale by the time the next request asks; within that one rendering every row sees the same
     * seats, which is what a page ought to show anyway. Describing a single record makes one and
     * throws it away, which costs exactly what it used to.
     */
    private final class Shared {

        private final Map<Long, Map<PocType, List<User>>> seats = new HashMap<>();
        private final Map<Long, List<Person>> addresses = new HashMap<>();
        /** Null values are deliberate: a record asked for and no longer there is remembered as gone. */
        private final Map<Long, Invoice> invoices = new HashMap<>();
        private final Map<Long, Payment> payments = new HashMap<>();

        /**
         * The invoices and payments a page of disputes is about, one read of each table. No other
         * kind keeps its POC anywhere but on the record already in hand, so there is nothing to
         * read ahead for them.
         */
        void readRecordsBehind(EmailEntityType type, List<?> entities) {
            if (type != DISPUTE) return;
            List<Dispute> disputes = entities.stream().map(Dispute.class::cast).toList();
            List<Long> invoiceIds = targetIds(disputes, DisputeTargetType.INVOICE);
            List<Long> paymentIds = targetIds(disputes, DisputeTargetType.PAYMENT);
            invoiceIds.forEach(id -> invoices.put(id, null));
            paymentIds.forEach(id -> payments.put(id, null));
            invoiceRepository.findAllById(invoiceIds).forEach(i -> invoices.put(i.getId(), i));
            paymentRepository.findAllById(paymentIds).forEach(p -> payments.put(p.getId(), p));
        }

        private List<Long> targetIds(List<Dispute> disputes, DisputeTargetType targetType) {
            return disputes.stream().filter(d -> d.getTargetType() == targetType)
                    .map(Dispute::getTargetId).filter(Objects::nonNull).distinct().toList();
        }

        /**
         * A dispute has no POC of its own; its record level is its target's (L3): an invoice
         * target's Sales POC, a payment target's Collection POC. The other is offered all the same,
         * with nobody holding it (L4), which is what leaving it out of this map means.
         */
        Map<EmailRole, Optional<User>> disputeFields(Dispute d) {
            return d.getTargetType() == DisputeTargetType.INVOICE
                    ? Map.of(SALES_POC, Optional.ofNullable(invoice(d.getTargetId())).map(Invoice::getSalesPoc))
                    : Map.of(COLLECTION_POC,
                            Optional.ofNullable(payment(d.getTargetId())).map(Payment::getCollectionPoc));
        }

        private Invoice invoice(Long id) {
            if (id == null) return null;
            if (invoices.containsKey(id)) return invoices.get(id);
            Invoice found = invoiceRepository.findById(id).orElse(null);
            invoices.put(id, found);
            return found;
        }

        private Payment payment(Long id) {
            if (id == null) return null;
            if (payments.containsKey(id)) return payments.get(id);
            Payment found = paymentRepository.findById(id).orElse(null);
            payments.put(id, found);
            return found;
        }

        /** The whole POC book of one customer, read once however many records share them (L2). */
        Map<PocType, List<User>> seatsOf(Long customerId) {
            return seats.computeIfAbsent(customerId, pocService::activeHoldersByType);
        }

        /** Every customer's own address and its logins', in one read of each table. */
        void readCustomers(Collection<Long> customerIds) {
            List<Long> ids = customerIds.stream().filter(Objects::nonNull).distinct().toList();
            if (ids.isEmpty()) return;
            Map<Long, List<User>> logins = directory.activeLoginsOf(ids);
            Map<Long, Customer> customers = customerRepository.findAllById(ids).stream()
                    .collect(Collectors.toMap(Customer::getId, customer -> customer));
            for (Long id : ids) {
                addresses.put(id, emailsOf(customers.get(id), logins.getOrDefault(id, List.of())));
            }
        }

        List<Person> addressesOf(Long customerId) {
            if (customerId == null) return List.of();
            List<Person> known = addresses.get(customerId);
            if (known != null) return known;
            List<Person> read = emailsOf(customerRepository.findById(customerId).orElse(null),
                    directory.activeLoginsOf(customerId));
            addresses.put(customerId, read);
            return read;
        }
    }

    // ---- bulk selection ---------------------------------------------------------------

    /**
     * Explicit ids, or every id matching the list's filter, re-resolved exactly as that list's own
     * bulk endpoint does — the list's schema as the caller may use it, plus the caller's scope — so a
     * bulk email can never reach a row the list would not show (AC-D6, AC-D10).
     */
    public List<Long> bulkIds(EmailEntityType type, BulkDtos.BulkRequest req) {
        List<Long> permitted = switch (type) {
            case CUSTOMER -> permittedIds(Customer.class, TableSchemas.CUSTOMERS, req,
                    scopeResolver.forCustomers().predicates());
            case INVOICE -> permittedIds(Invoice.class, TableSchemas.INVOICES, req,
                    scopeResolver.forInvoices().predicates());
            case PAYMENT -> permittedIds(Payment.class, TableSchemas.PAYMENTS, req,
                    scopeResolver.forPayments().predicates());
            case PROMISE -> permittedIds(PaymentPromise.class, TableSchemas.PROMISES, req,
                    scopeResolver.forPromises().predicates());
            case DISPUTE -> permittedIds(Dispute.class, TableSchemas.DISPUTES, req,
                    scopeResolver.forDisputes().predicates());
            case PRODUCT -> permittedIds(Product.class, TableSchemas.PRODUCTS, req, List.of());
            case USER -> permittedIds(User.class, TableSchemas.USERS, req, List.of());
            case ROLE -> permittedIds(Role.class, TableSchemas.ROLES, req, List.of());
        };
        if (req.allMatching()) return permitted;
        if (req.ids() == null || req.ids().isEmpty()) {
            throw new BadRequestException("Provide ids or set selectAllMatchingFilter");
        }
        Set<Long> reachable = new HashSet<>(permitted);
        return req.ids().stream().filter(reachable::contains).toList();
    }

    private <T> List<Long> permittedIds(Class<T> entity, TableSchema schema, BulkDtos.BulkRequest req,
                                        List<PredicateFactory> scope) {
        TableQuery query = TableQuery.parseUnpaged(schema.visibleTo(currentUser.isCustomer()),
                req.sort(), req.filters());
        return queryExecutor.ids(entity, schema, query, scope, TableQueryExecutor.BULK_ID_LIMIT);
    }

    // ---- suggestions -----------------------------------------------------------------

    /**
     * A suggested email for a record that was just created or changed (§6 Suggestions), or empty
     * when there is nothing to suggest for that event. The target must come from {@link #load}, which
     * has already checked the caller can see it. A customer login is never offered a USER token. Dates
     * are the day in {@code zone}, the reader's.
     */
    @Transactional(readOnly = true)
    public Optional<EmailDtos.Suggestion> suggest(Target target, Event event, ZoneId zone) {
        Object entity = loadUnscoped(target.type(), target.id())
                .orElseThrow(() -> new NotFoundException("Record not found"));
        EmailDtos.Suggestion suggestion = suggestion(target, entity, event, zone);
        if (suggestion == null) return Optional.empty();
        boolean restricted = currentUser.isCustomer();
        List<EmailToken> to = suggestion.to().stream()
                .filter(t -> !restricted || !"USER".equals(t.type()))
                .toList();
        return Optional.of(new EmailDtos.Suggestion(
                EmailText.fit(suggestion.subject(), FieldLimits.EMAIL_SUBJECT),
                EmailText.fit(suggestion.body(), FieldLimits.EMAIL_BODY), to));
    }

    private EmailDtos.Suggestion suggestion(Target target, Object entity, Event event, ZoneId zone) {
        EmailToken customer = EmailToken.customer();
        return switch (target.type()) {
            case CUSTOMER -> {
                if (event != Event.CREATED) yield null;
                Customer c = (Customer) entity;
                yield new EmailDtos.Suggestion("Welcome, " + c.getName(),
                        "Hello " + c.getName() + ",\n\n"
                                + "Your account has been set up. Your invoices and payment receipts will be sent to this address.\n\n"
                                + "Thank you.",
                        List.of(customer));
            }
            case INVOICE -> {
                if (event != Event.CREATED) yield null;
                Invoice i = (Invoice) entity;
                yield new EmailDtos.Suggestion(
                        "Invoice " + i.getInvoiceNumber() + " for " + EmailText.money(i.getTotal()),
                        "Hello " + i.getCustomer().getName() + ",\n\n"
                                + "Invoice " + i.getInvoiceNumber() + " has been issued.\n\n"
                                + "Date: " + EmailText.date(i.getInvoiceDate(), zone) + "\n"
                                + "Total: " + EmailText.money(i.getTotal()) + "\n"
                                + "Balance due: " + EmailText.money(i.getBalance()) + "\n\n"
                                + "Thank you.",
                        List.of(customer));
            }
            case PAYMENT -> {
                if (event != Event.CREATED) yield null;
                Payment p = (Payment) entity;
                String method = p.getMethod() == null || p.getMethod().isBlank()
                        ? "" : "Method: " + p.getMethod().trim() + "\n";
                yield new EmailDtos.Suggestion("Payment of " + EmailText.money(p.getAmount()) + " received",
                        "Hello " + p.getCustomer().getName() + ",\n\n"
                                + "We have received your payment of " + EmailText.money(p.getAmount()) + ".\n\n"
                                + "Date: " + EmailText.date(p.getPaidAt(), zone) + "\n"
                                + method + "\n"
                                + "Thank you.",
                        List.of(customer));
            }
            case PROMISE -> {
                PaymentPromise p = (PaymentPromise) entity;
                String terms = EmailText.money(p.getAmount()) + " by " + EmailText.date(p.getPromisedDate());
                // The customer's collections seats and the promise's own Collection POC (§3).
                List<EmailToken> to = List.of(customer, EmailToken.role(RoleRef.customer(COLLECTION_POC)),
                        EmailToken.role(RoleRef.record(COLLECTION_POC)));
                yield event == Event.CREATED
                        ? new EmailDtos.Suggestion("Payment promise: " + terms,
                                "Hello " + p.getCustomer().getName() + ",\n\n"
                                        + "This confirms your promise to pay " + terms + ".\n\n"
                                        + "Thank you.",
                                to)
                        : new EmailDtos.Suggestion("Payment promise updated: " + terms,
                                "Hello " + p.getCustomer().getName() + ",\n\n"
                                        + "Your payment promise has been updated: " + terms + ".\n\n"
                                        + "Status: " + EmailText.humanize(p.getStatus()) + "\n\n"
                                        + "Thank you.",
                                to);
            }
            case DISPUTE -> disputeSuggestion((Dispute) entity, event);
            case PRODUCT -> {
                if (event != Event.CREATED) yield null;
                Product p = (Product) entity;
                yield new EmailDtos.Suggestion("New product: " + p.getName(),
                        p.getName() + " is now available at " + EmailText.money(p.getPrice()) + "."
                                + (p.getDescription() == null || p.getDescription().isBlank()
                                        ? "" : "\n\n" + p.getDescription().trim()),
                        List.of());
            }
            case USER -> {
                if (event != Event.CREATED) yield null;
                User u = (User) entity;
                yield new EmailDtos.Suggestion("Your Gene Invoice account",
                        "Hello " + EmailText.nameOf(u) + ",\n\n"
                                + "An account has been created for you in Gene Invoice.\n\n"
                                + "Username: " + u.getUsername() + "\n\n"
                                + "Thank you.",
                        List.of(u.getCustomerId() == null ? EmailToken.user(u.getId()) : customer));
            }
            case ROLE -> {
                if (event != Event.CREATED) yield null;
                Role r = (Role) entity;
                yield new EmailDtos.Suggestion("New role: " + r.getName(),
                        "A new role, " + r.getName() + ", has been added."
                                + (r.getDescription() == null || r.getDescription().isBlank()
                                        ? "" : "\n\n" + r.getDescription().trim()),
                        List.of());
            }
        };
    }

    private EmailDtos.Suggestion disputeSuggestion(Dispute d, Event event) {
        String customerName = customerRepository.findById(d.getCustomerId()).map(Customer::getName).orElse("The customer");
        String target = d.getTargetType() == DisputeTargetType.INVOICE
                ? invoiceRepository.findById(d.getTargetId())
                        .map(i -> "Invoice " + i.getInvoiceNumber()).orElse("Invoice #" + d.getTargetId())
                : "Payment #" + d.getTargetId();
        if (event == Event.CREATED) {
            // The customer's people, and the one the target itself names (§3).
            List<EmailToken> to = new ArrayList<>(List.of(
                    EmailToken.role(RoleRef.customer(CUSTOMER_SUCCESS_POC)),
                    EmailToken.role(RoleRef.customer(COLLECTION_POC))));
            to.add(EmailToken.role(RoleRef.record(d.getTargetType() == DisputeTargetType.INVOICE
                    ? SALES_POC : COLLECTION_POC)));
            return new EmailDtos.Suggestion("Dispute #" + d.getId() + " raised on " + target,
                    customerName + " has raised a dispute on " + target + ".\n\n"
                            + "Reason:\n" + d.getReason(),
                    to);
        }
        String outcome = d.getStatus() == DisputeStatus.APPROVED ? "approved"
                : d.getStatus() == DisputeStatus.DENIED ? "denied" : "updated";
        return new EmailDtos.Suggestion("Dispute #" + d.getId() + " " + outcome,
                "Hello " + customerName + ",\n\n"
                        + "Your dispute on " + target + " has been " + outcome + "."
                        + (d.getAdminNotes() == null || d.getAdminNotes().isBlank()
                                ? "" : "\n\nNotes:\n" + d.getAdminNotes().trim())
                        + "\n\nThank you.",
                List.of(EmailToken.customer()));
    }
}

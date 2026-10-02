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
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.dispute.DisputeStatus;
import com.geneinvoice.history.Records;
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
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.region.Region;
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
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.geneinvoice.email.EmailEntityType.*;
import static com.geneinvoice.email.EmailRole.COLLECTION_POC;
import static com.geneinvoice.email.EmailRole.CUSTOMER_SUCCESS_POC;
import static com.geneinvoice.email.EmailRole.SALES_POC;

@Component
@RequiredArgsConstructor
public class EmailTargets {

    private static final Map<EmailEntityType, String> VIEW_PRIVILEGE = Map.of(
            CUSTOMER, Privileges.CUSTOMER_VIEW,
            INVOICE, Privileges.INVOICE_VIEW,
            PAYMENT, Privileges.PAYMENT_VIEW,
            PROMISE, Privileges.PROMISE_VIEW,
            DISPUTE, Privileges.DISPUTE_VIEW,
            PRODUCT, Privileges.PRODUCT_VIEW,
            USER, Privileges.USER_VIEW,
            ROLE, Privileges.ROLE_VIEW);

    private static final Set<EmailEntityType> CUSTOMER_READABLE = EnumSet.of(CUSTOMER, INVOICE, PAYMENT, PROMISE, DISPUTE);

    private static final Set<EmailEntityType> WITHOUT_CUSTOMER = EnumSet.of(PRODUCT, ROLE);

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
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PocService pocService;
    private final ScopeResolver scopeResolver;
    private final TableQueryExecutor queryExecutor;
    private final EmailDirectory directory;
    private final CurrentUser currentUser;
    private final Records records;

    public record Person(Long userId, String name, String address, Long customerId, boolean internal) {
        static Person of(User u) {
            return new Person(u.getId(), EmailText.nameOf(u), Emails.normalize(u.getEmail()),
                    u.getCustomerId(), u.getCustomerId() == null);
        }
    }

    /** {@code regionId} is the record's region, and null for a product, a user or a role, which have none. */
    public record Target(EmailEntityType type, Long id, String label, Long customerId, Long regionId,
                         Map<RoleRef, List<Person>> holders, List<Person> customerEmails) {

        public String link() {
            return EmailTargets.link(type, id);
        }

        public List<Person> holders(RoleRef role) {
            return holders.getOrDefault(role, List.of());
        }

        /** Who sends when the role is the From: an email has one sender, so the first holder (L5). */
        public Optional<Person> sender(RoleRef role) {
            return holders(role).stream().findFirst();
        }
    }

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

    public List<RoleRef> rolesOffered(EmailEntityType type) {
        return offeredRoles(type);
    }

    /** The roles offered on a kind of record, customer level first; needs no instance (§5.9.1, CF-3). */
    public static List<RoleRef> offeredRoles(EmailEntityType type) {
        return ROLES_OFFERED.get(type);
    }

    public List<RoleRef> rolesOffered(Target target) {
        if (target.type() == USER && target.customerId() == null) return List.of();
        return rolesOffered(target.type());
    }

    public static RoleLevel defaultLevel(EmailEntityType type, EmailRole role) {
        return CUSTOMER_ROLES.get(type).contains(role) ? RoleLevel.CUSTOMER : RoleLevel.RECORD;
    }

    public boolean mayHaveCustomer(EmailEntityType type) {
        return !WITHOUT_CUSTOMER.contains(type);
    }

    public void requireTypeAccess(EmailEntityType type) {
        if (!currentUser.has(VIEW_PRIVILEGE.get(type))) {
            throw new AccessDeniedException("Not allowed");
        }
        if (currentUser.isCustomer() && !CUSTOMER_READABLE.contains(type)) {
            throw new AccessDeniedException("Not allowed");
        }
    }

    @Transactional(readOnly = true)
    public void requireVisible(EmailEntityType type, Long id) {
        requireTypeAccess(type);
        loadScoped(type, id);
    }

    @Transactional(readOnly = true)
    public Target load(EmailEntityType type, Long id) {
        requireTypeAccess(type);
        return describe(type, loadScoped(type, id));
    }

    /**
     * The record as it is, whoever is signed in and whether or not they could open it: for telling the people a
     * rule or an approval assigned (§5.10.5, §5.10.12), who are checked one by one. Empty when it does not exist.
     * Never throws for a missing record, so it can be asked inside a write that must go on.
     */
    @Transactional(readOnly = true)
    public Optional<Target> findUnscoped(EmailEntityType type, Long id) {
        return loadUnscoped(type, id).map(entity -> describe(type, entity));
    }

    /** A record to send email about: visible (404), and in a region where the caller may send (403). */
    @Transactional(readOnly = true)
    public Target loadForSend(EmailEntityType type, Long id) {
        Target target = load(type, id);
        if (target.regionId() != null) currentUser.requireIn(Privileges.EMAIL_SEND, target.regionId());
        return target;
    }

    private Optional<?> loadUnscoped(EmailEntityType type, Long id) {
        return switch (type) {
            case CUSTOMER -> records.find(Customer.class, id);
            case INVOICE -> records.find(Invoice.class, id);
            case PAYMENT -> records.find(Payment.class, id);
            case PROMISE -> records.find(PaymentPromise.class, id);
            case DISPUTE -> records.find(Dispute.class, id);
            case PRODUCT -> records.find(Product.class, id);
            case USER -> userRepository.findById(id);
            case ROLE -> roleRepository.findById(id);
        };
    }

    private Object loadScoped(EmailEntityType type, Long id) {
        return switch (type) {
            case CUSTOMER -> customerService.get(id);
            case INVOICE -> invoiceService.get(id);
            case PAYMENT -> paymentService.get(id);
            case PROMISE -> promiseService.get(id);
            case DISPUTE -> disputeService.get(id);
            case PRODUCT -> records.find(Product.class, id)
                    .orElseThrow(() -> new NotFoundException("Product not found"));
            case USER -> userRepository.findById(id)
                    .orElseThrow(() -> new NotFoundException("User not found"));
            case ROLE -> roleRepository.findById(id)
                    .orElseThrow(() -> new NotFoundException("Role not found"));
        };
    }

    private Target describe(EmailEntityType type, Object entity) {
        return switch (type) {
            case CUSTOMER -> {
                Customer c = (Customer) entity;
                yield target(type, c.getId(), "Customer " + c.getName(), c.getId(), Region.idOf(c.getRegion()),
                        Map.of());
            }
            case INVOICE -> {
                Invoice i = (Invoice) entity;
                yield target(type, i.getId(), "Invoice " + i.getInvoiceNumber(), i.getCustomer().getId(),
                        Region.idOf(i.getRegion()), Map.of(SALES_POC, Optional.ofNullable(i.getSalesPoc())));
            }
            case PAYMENT -> {
                Payment p = (Payment) entity;
                yield target(type, p.getId(), "Payment #" + p.getId(), p.getCustomer().getId(),
                        Region.idOf(p.getRegion()), Map.of(COLLECTION_POC, Optional.ofNullable(p.getCollectionPoc())));
            }
            case PROMISE -> {
                PaymentPromise p = (PaymentPromise) entity;
                yield target(type, p.getId(), "Promise #" + p.getId(), p.getCustomer().getId(),
                        Region.idOf(p.getRegion()), Map.of(COLLECTION_POC, Optional.ofNullable(p.getCollectionPoc())));
            }
            case DISPUTE -> {
                Dispute d = (Dispute) entity;
                yield target(type, d.getId(), "Dispute #" + d.getId(), d.getCustomerId(), Region.idOf(d.getRegion()),
                        disputeOwnFields(d));
            }
            case PRODUCT -> {
                Product p = (Product) entity;
                yield target(type, p.getId(), "Product " + p.getName(), null, null, Map.of());
            }
            case USER -> {
                User u = (User) entity;
                yield target(type, u.getId(), "User " + u.getUsername(), u.getCustomerId(), null, Map.of());
            }
            case ROLE -> {
                Role r = (Role) entity;
                yield target(type, r.getId(), "Role " + r.getName(), null, null, Map.of());
            }
        };
    }

    /** A dispute's own POC is its target's; a dispute on the customer as a whole has none (S2). */
    private Map<EmailRole, Optional<User>> disputeOwnFields(Dispute d) {
        return switch (d.getTargetType()) {
            case INVOICE -> Map.of(SALES_POC, records.find(Invoice.class, d.getTargetId()).map(Invoice::getSalesPoc));
            case PAYMENT -> Map.of(COLLECTION_POC,
                    records.find(Payment.class, d.getTargetId()).map(Payment::getCollectionPoc));
            case CUSTOMER -> Map.of();
        };
    }

    private Target target(EmailEntityType type, Long id, String label, Long customerId, Long regionId,
                          Map<EmailRole, Optional<User>> ownFields) {
        return new Target(type, id, EmailText.fit(label, Email.LABEL_MAX), customerId, regionId,
                holders(type, customerId, regionId, ownFields), customerEmails(customerId));
    }

    /**
     * Who holds each role on the record. Someone with no access to the record's region holds none of them
     * (§5.6.7): the customer's seats are filtered by {@link PocService#activeHoldersByType}, and the record's own
     * POC fields here.
     */
    private Map<RoleRef, List<Person>> holders(EmailEntityType type, Long customerId, Long regionId,
                                               Map<EmailRole, Optional<User>> ownFields) {
        Map<RoleRef, List<Person>> holders = new LinkedHashMap<>();
        Map<PocType, List<User>> book = null;
        List<User> ownPeople = ownFields.values().stream().flatMap(Optional::stream).filter(User::isActive).toList();
        List<User> ownReachable = regionId == null ? ownPeople : pocService.withRegionAccess(ownPeople, regionId);
        for (RoleRef ref : ROLES_OFFERED.get(type)) {
            List<User> people;
            if (ref.level() == RoleLevel.RECORD) {
                people = ownFields.getOrDefault(ref.role(), Optional.empty()).stream()
                        .filter(ownReachable::contains).toList();
            } else if (customerId == null) {
                people = List.of();
            } else {
                if (book == null) book = pocService.activeHoldersByType(customerId);
                people = book.getOrDefault(ref.role().pocType(), List.of());
            }
            List<Person> active = people.stream().filter(User::isActive).map(Person::of).toList();
            if (!active.isEmpty()) holders.put(ref, active);
        }
        return holders;
    }

    private List<Person> customerEmails(Long customerId) {
        if (customerId == null) return List.of();
        List<Person> emails = new ArrayList<>();
        customerRepository.findById(customerId).ifPresent(c -> {
            String address = Emails.normalize(c.getEmail());
            if (address != null) emails.add(new Person(null, c.getName(), address, c.getId(), false));
        });
        for (User login : directory.activeLoginsOf(customerId)) {
            Person p = Person.of(login);
            if (p.address() != null) emails.add(p);
        }
        return emails;
    }

    public List<Long> bulkIds(EmailEntityType type, BulkDtos.BulkRequest req) {
        List<Long> permitted = switch (type) {
            case CUSTOMER -> permittedIds(Customer.class, TableSchemas.CUSTOMERS, req, sendable(ScopeResolver.Kind.CUSTOMER));
            case INVOICE -> permittedIds(Invoice.class, TableSchemas.INVOICES, req, sendable(ScopeResolver.Kind.INVOICE));
            case PAYMENT -> permittedIds(Payment.class, TableSchemas.PAYMENTS, req, sendable(ScopeResolver.Kind.PAYMENT));
            case PROMISE -> permittedIds(PaymentPromise.class, TableSchemas.PROMISES, req, sendable(ScopeResolver.Kind.PROMISE));
            case DISPUTE -> permittedIds(Dispute.class, TableSchemas.DISPUTES, req, sendable(ScopeResolver.Kind.DISPUTE));
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

    /** The records of a kind the caller sees, in the regions asked about, where they may also send email. */
    private List<PredicateFactory> sendable(ScopeResolver.Kind kind) {
        return scopeResolver.scope(kind, ScopeResolver.Access.VIEW, ScopeResolver.Reach.SELECTED,
                Privileges.EMAIL_SEND).predicates();
    }

    private <T> List<Long> permittedIds(Class<T> entity, TableSchema schema, BulkDtos.BulkRequest req,
                                        List<PredicateFactory> scope) {
        TableQuery query = TableQuery.parseUnpaged(schema.visibleTo(currentUser.isCustomer()),
                req.sort(), req.filters());
        return queryExecutor.ids(entity, schema, query, scope, TableQueryExecutor.BULK_ID_LIMIT);
    }

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
        String target = switch (d.getTargetType()) {
            case INVOICE -> invoiceRepository.findById(d.getTargetId())
                    .map(i -> "Invoice " + i.getInvoiceNumber()).orElse("Invoice #" + d.getTargetId());
            case PAYMENT -> "Payment #" + d.getTargetId();
            case CUSTOMER -> "Customer " + customerName;
        };
        if (event == Event.CREATED) {
            List<EmailToken> to = new ArrayList<>(List.of(
                    EmailToken.role(RoleRef.customer(CUSTOMER_SUCCESS_POC)),
                    EmailToken.role(RoleRef.customer(COLLECTION_POC))));
            switch (d.getTargetType()) {
                case INVOICE -> to.add(EmailToken.role(RoleRef.record(SALES_POC)));
                case PAYMENT -> to.add(EmailToken.role(RoleRef.record(COLLECTION_POC)));
                case CUSTOMER -> { }
            }
            // An internal dispute is staff's own review: nothing in it speaks for the customer.
            return new EmailDtos.Suggestion("Dispute #" + d.getId() + " raised on " + target,
                    (d.isInternal() ? "A dispute has been raised for review on " + target
                            : customerName + " has raised a dispute on " + target) + ".\n\n"
                            + "Reason:\n" + d.getReason(),
                    to);
        }
        if (d.isInternal()) {
            // Decided internal work is reported to its team, never to the customer, who never saw it.
            String outcome = d.getStatus() == DisputeStatus.APPROVED ? "approved"
                    : d.getStatus() == DisputeStatus.DENIED ? "denied" : "updated";
            return new EmailDtos.Suggestion("Dispute #" + d.getId() + " " + outcome,
                    "The dispute raised for review on " + target + " has been " + outcome + "."
                            + (d.getAdminNotes() == null || d.getAdminNotes().isBlank()
                                    ? "" : "\n\nNotes:\n" + d.getAdminNotes().trim()),
                    List.of(EmailToken.role(RoleRef.customer(CUSTOMER_SUCCESS_POC)),
                            EmailToken.role(RoleRef.customer(COLLECTION_POC))));
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

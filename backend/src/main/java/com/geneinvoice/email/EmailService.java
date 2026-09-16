package com.geneinvoice.email;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.bulk.BulkDtos;
import com.geneinvoice.common.bulk.BulkExecutor;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.PredicateFactory;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchemas;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.poc.ScopeResolver;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.role.Role;
import com.geneinvoice.role.RoleRepository;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Sends and reads in-app emails. Sending works out the recipients once — the sender's and every
 * role's details as they are at that moment — and stores them on each email it creates, so a later
 * change to a role, its members or a mailbox never rewrites what was sent.
 */
@Service
@RequiredArgsConstructor
public class EmailService {

    /** Customer logins hold this role; its members have no Inbox, so it is never an email party. */
    static final String CUSTOMER_ROLE = "CUSTOMER";

    private final EmailRepository emailRepository;
    private final EmailDeliveryRepository deliveryRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final CustomerService customerService;
    private final InvoiceService invoiceService;
    private final ScopeResolver scopeResolver;
    private final TableQueryExecutor queryExecutor;
    private final BulkExecutor bulkExecutor;
    private final CurrentUser currentUser;

    // ---- sending -----------------------------------------------------------------

    /** One email about a customer or an invoice. */
    @Transactional
    public EmailDtos.EmailDto send(EmailDtos.SendRequest req) {
        User me = requireStaff();
        Target target = target(req.customerId(), req.invoiceId());
        Draft draft = draft(req.compose());
        List<String> addresses = addressesFor(target, req.to());
        if (draft.inboxUserIds().isEmpty() && addresses.isEmpty()) {
            throw new BadRequestException(
                    "No one would receive this email: " + target.label() + " has no email address, "
                            + "and no one else in To would receive it");
        }
        return toDto(persist(draft, target, addresses, me));
    }

    /**
     * One email per selected customer or invoice, from a form filled in once. Each email commits on
     * its own. A row with nobody to receive its email — its customer has no address and nobody else
     * is in To — is skipped and reported, never dropped silently.
     */
    public BulkDtos.BulkResult sendBulk(EmailDtos.BulkSendRequest req) {
        User me = requireStaff();
        EmailDtos.TargetType type = req.targetType();
        requireViewOf(type);
        EmailDtos.Compose compose = req.email();
        if (compose.to() != null && !compose.to().addresses().isEmpty()) {
            throw new BadRequestException("A bulk send cannot pick particular customer addresses: "
                    + "choose every address of each row's customer instead (allCustomerEmails)");
        }
        Draft draft = draft(compose);

        BulkDtos.BulkRequest selection = new BulkDtos.BulkRequest("SEND_EMAIL", req.ids(),
                req.selectAllMatchingFilter(), req.sort(), req.filters(), null);
        List<Long> ids = resolveIds(type, selection);
        boolean truncated = selection.allMatching() && ids.size() >= TableQueryExecutor.BULK_ID_LIMIT;

        return bulkExecutor.run(selection, ids, truncated, id -> {
            Target target = type == EmailDtos.TargetType.CUSTOMER
                    ? new Target(customerService.get(id), null)
                    : Target.of(invoiceService.get(id));
            List<String> addresses = draft.allCustomerEmails()
                    ? target.customer().allEmailAddresses()
                    : List.of();
            if (draft.inboxUserIds().isEmpty() && addresses.isEmpty()) {
                throw new BulkExecutor.IneligibleException(target.label()
                        + " has no email address, and no one else in To would receive it");
            }
            persist(draft, target, addresses, me);
        });
    }

    /**
     * What an email is about, and the customer whose addresses it may go to. Reached through the
     * customer and invoice services, so a record outside the caller's scope is as unreachable here
     * as it is on its own page.
     */
    record Target(Customer customer, Invoice invoice) {
        static Target of(Invoice invoice) {
            return new Target(invoice.getCustomer(), invoice);
        }

        String label() {
            return invoice == null
                    ? customer.getName()
                    : invoice.getInvoiceNumber() + "'s customer (" + customer.getName() + ")";
        }
    }

    private Target target(Long customerId, Long invoiceId) {
        if ((customerId == null) == (invoiceId == null)) {
            throw new BadRequestException("Link the email to either a customer or an invoice");
        }
        if (customerId != null) {
            requireViewOf(EmailDtos.TargetType.CUSTOMER);
            return new Target(customerService.get(customerId), null);
        }
        requireViewOf(EmailDtos.TargetType.INVOICE);
        return Target.of(invoiceService.get(invoiceId));
    }

    /** Someone named in To, or the sender, as they were when the form was sent. */
    private record Party(EmailPartyType type, Long userId, Long roleId, String name, String address,
                         List<EmailRoleMember> members) {}

    /**
     * The form, checked and looked up once per send: the sender, the users and roles named in To
     * with each role's members at this moment, and everyone who gets an Inbox copy — each person
     * once, however many ways they were named.
     */
    private record Draft(Party from, List<Party> named, Set<Long> inboxUserIds,
                         boolean allCustomerEmails, String subject, String body) {}

    private Draft draft(EmailDtos.Compose compose) {
        EmailDtos.Recipients to = compose.to();
        if (to.users().isEmpty() && to.roles().isEmpty() && to.addresses().isEmpty() && !to.allAddresses()) {
            throw new BadRequestException("Add at least one recipient");
        }
        Party from = sender(compose.from());

        List<Party> named = new ArrayList<>();
        Set<Long> inbox = new LinkedHashSet<>();
        for (Long userId : new LinkedHashSet<>(to.users())) {
            User u = requireActiveStaff(userId, "recipient");
            named.add(new Party(EmailPartyType.USER, u.getId(), null, displayName(u), u.getEmail(), List.of()));
            inbox.add(u.getId());
        }
        List<Role> emptyRoles = new ArrayList<>();
        for (Long roleId : new LinkedHashSet<>(to.roles())) {
            Role r = requireEmailRole(roleId);
            List<EmailRoleMember> members = userRepository.findActiveStaffInRole(r.getId()).stream()
                    .map(u -> new EmailRoleMember(u.getId(), displayName(u), u.getEmail()))
                    .toList();
            if (members.isEmpty()) emptyRoles.add(r);
            named.add(new Party(EmailPartyType.ROLE, null, r.getId(), r.getName(), r.getEmail(), members));
            members.forEach(m -> inbox.add(m.getUserId()));
        }
        // A To line that could reach nobody on any record is one clear refusal, not an email per
        // record that is then skipped.
        if (inbox.isEmpty() && to.addresses().isEmpty() && !to.allAddresses()) {
            throw new BadRequestException("No one would receive this email: "
                    + String.join(", ", emptyRoles.stream().map(Role::getName).toList())
                    + (emptyRoles.size() == 1 ? " has" : " have") + " no active members");
        }
        String body = compose.body() == null ? "" : compose.body();
        return new Draft(from, named, inbox, to.allAddresses(), compose.subject().trim(), body);
    }

    private Party sender(EmailDtos.Sender from) {
        return switch (from.type()) {
            case USER -> {
                User u = requireActiveStaff(from.id(), "sender");
                yield new Party(EmailPartyType.USER, u.getId(), null, displayName(u), u.getEmail(), List.of());
            }
            // The role's mailbox is read now and kept on the email, whatever it is changed to later.
            case ROLE -> {
                Role r = requireEmailRole(from.id());
                yield new Party(EmailPartyType.ROLE, null, r.getId(), r.getName(), r.getEmail(), List.of());
            }
            default -> throw new BadRequestException("An email is from a user or a role");
        };
    }

    /** The customer addresses a single email goes to: the ones picked, or all of them. */
    private List<String> addressesFor(Target target, EmailDtos.Recipients to) {
        List<String> onCustomer = target.customer().allEmailAddresses();
        if (to.allAddresses()) return onCustomer;
        Map<String, String> byKey = new LinkedHashMap<>();
        for (String address : onCustomer) byKey.put(address.toLowerCase(Locale.ROOT), address);
        Map<String, String> picked = new LinkedHashMap<>();
        for (String raw : to.addresses()) {
            String key = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            String stored = byKey.get(key);
            if (stored == null) {
                throw new BadRequestException(
                        "'" + raw + "' is not an email address of " + target.customer().getName());
            }
            picked.putIfAbsent(key, stored);
        }
        return List.copyOf(picked.values());
    }

    private Email persist(Draft draft, Target target, List<String> addresses, User me) {
        Party from = draft.from();
        Email email = Email.builder()
                .customer(target.customer())
                .invoice(target.invoice())
                .fromType(from.type()).fromUserId(from.userId()).fromRoleId(from.roleId())
                .fromName(from.name()).fromAddress(from.address())
                .subject(draft.subject())
                .body(draft.body())
                .sentByUserId(me.getId())
                .sentByName(displayName(me))
                .sentAt(Instant.now())
                .build();
        int order = 0;
        for (Party p : draft.named()) {
            email.getRecipients().add(EmailRecipient.builder()
                    .email(email).sortOrder(order++)
                    .type(p.type()).userId(p.userId()).roleId(p.roleId())
                    .name(p.name()).address(p.address())
                    .members(new ArrayList<>(p.members()))
                    .build());
        }
        for (String address : addresses) {
            email.getRecipients().add(EmailRecipient.builder()
                    .email(email).sortOrder(order++)
                    .type(EmailPartyType.CUSTOMER_EMAIL).address(address)
                    .build());
        }
        Email saved = emailRepository.save(email);
        for (Long userId : draft.inboxUserIds()) {
            deliveryRepository.save(EmailDelivery.builder().email(saved).userId(userId).build());
        }
        return saved;
    }

    private List<Long> resolveIds(EmailDtos.TargetType type, BulkDtos.BulkRequest req) {
        List<Long> permitted = type == EmailDtos.TargetType.CUSTOMER
                ? customerService.idsMatching(
                        TableQuery.parseUnpaged(TableSchemas.CUSTOMERS, req.sort(), req.filters()),
                        TableQueryExecutor.BULK_ID_LIMIT)
                : invoiceService.idsMatching(
                        TableQuery.parseUnpaged(TableSchemas.INVOICES, req.sort(), req.filters()),
                        TableQueryExecutor.BULK_ID_LIMIT);
        if (req.allMatching()) return permitted;
        if (req.ids() == null || req.ids().isEmpty()) {
            throw new BadRequestException("Provide ids or set selectAllMatchingFilter");
        }
        return req.ids().stream().filter(permitted::contains).toList();
    }

    // ---- the compose form's choices ------------------------------------------------

    @Transactional(readOnly = true)
    public List<EmailDtos.StaffDto> staff(String query, int limit) {
        requireStaff();
        String pattern = (query == null || query.isBlank())
                ? null
                : "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
        return userRepository.findActiveStaff(pattern, PageRequest.of(0, Math.min(Math.max(limit, 1), 50)))
                .stream().map(EmailDtos.StaffDto::from).toList();
    }

    @Transactional(readOnly = true)
    public List<EmailDtos.RoleOptionDto> roles() {
        requireStaff();
        Map<Long, Long> members = new HashMap<>();
        for (Object[] row : userRepository.countActiveStaffByRole()) {
            members.put((Long) row[0], ((Number) row[1]).longValue());
        }
        return roleRepository.findAll(Sort.by("name")).stream()
                .filter(r -> !CUSTOMER_ROLE.equalsIgnoreCase(r.getName()))
                .map(r -> new EmailDtos.RoleOptionDto(r.getId(), r.getName(), r.getEmail(),
                        members.getOrDefault(r.getId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public EmailDtos.AddressesDto addresses(Long customerId, Long invoiceId) {
        requireStaff();
        Target t = target(customerId, invoiceId);
        return new EmailDtos.AddressesDto(t.customer().getId(), t.customer().getName(),
                t.invoice() == null ? null : t.invoice().getId(),
                t.invoice() == null ? null : t.invoice().getInvoiceNumber(),
                t.customer().allEmailAddresses());
    }

    // ---- the Email tab -----------------------------------------------------------

    /** The customer's own emails and those about its invoices, newest first. */
    @Transactional(readOnly = true)
    public PageResponse<EmailDtos.EmailDto> pageForCustomer(Long customerId, Integer page, Integer size) {
        requireStaff();
        requireViewOf(EmailDtos.TargetType.CUSTOMER);
        customerService.get(customerId);
        return page(page, size, List.of(
                (root, q, cb) -> cb.equal(root.get("customer").get("id"), customerId),
                invoiceInScope()));
    }

    @Transactional(readOnly = true)
    public PageResponse<EmailDtos.EmailDto> pageForInvoice(Long invoiceId, Integer page, Integer size) {
        requireStaff();
        requireViewOf(EmailDtos.TargetType.INVOICE);
        invoiceService.get(invoiceId);
        return page(page, size, List.of(
                (root, q, cb) -> cb.equal(root.get("invoice").get("id"), invoiceId)));
    }

    private PageResponse<EmailDtos.EmailDto> page(Integer page, Integer size, List<PredicateFactory> where) {
        TableQuery query = TableQuery.parse(TableSchemas.EMAILS, page, size, null, List.of());
        var result = queryExecutor.run(Email.class, TableSchemas.EMAILS, query, where,
                List.of("customer", "invoice"));
        return PageResponse.of(result.content().stream().map(this::toDto).toList(),
                query, result.total(), List.of());
    }

    /**
     * An invoice's email is listed on its customer only where the invoice itself may be seen: a
     * Sales POC held to their own book does not read about another rep's invoices.
     */
    private PredicateFactory invoiceInScope() {
        List<PredicateFactory> invoiceScope = scopeResolver.forInvoices().predicates();
        if (invoiceScope.isEmpty()) return (root, q, cb) -> null;
        return (root, q, cb) -> {
            Subquery<Long> sq = q.subquery(Long.class);
            Root<Invoice> invoice = sq.from(Invoice.class);
            List<Predicate> parts = new ArrayList<>();
            parts.add(cb.equal(invoice.get("id"), root.get("invoice").get("id")));
            for (PredicateFactory f : invoiceScope) parts.add(f.build(invoice, q, cb));
            sq.select(cb.literal(1L)).where(parts.toArray(new Predicate[0]));
            return cb.or(cb.isNull(root.get("invoice")), cb.exists(sq));
        };
    }

    // ---- the Inbox -----------------------------------------------------------------

    /** The emails this user received, named directly or through a role, newest first. */
    @Transactional(readOnly = true)
    public PageResponse<EmailDtos.InboxItemDto> inbox(Integer page, Integer size, String sort,
                                                      List<String> filters) {
        Long me = requireStaff().getId();
        TableQuery query = TableQuery.parse(TableSchemas.INBOX, page, size, sort, filters);
        var result = queryExecutor.run(EmailDelivery.class, TableSchemas.INBOX, query,
                List.of((root, q, cb) -> cb.equal(root.get("userId"), me)), List.of("email"));
        return PageResponse.of(result.content().stream().map(this::toInboxItem).toList(),
                query, result.total(), List.of());
    }

    @Transactional(readOnly = true)
    public EmailDtos.InboxItemDto inboxItem(Long emailId) {
        return toInboxItem(requireDelivery(emailId));
    }

    /** Marks one email read for this user only; everyone else's copy keeps its own status. */
    @Transactional
    public EmailDtos.InboxItemDto markRead(Long emailId) {
        EmailDelivery d = requireDelivery(emailId);
        if (d.getReadAt() == null) {
            d.setReadAt(Instant.now());
            deliveryRepository.save(d);
        }
        return toInboxItem(d);
    }

    /** Every unread email in the caller's Inbox, not just the page on screen. */
    @Transactional
    public int markAllRead() {
        return deliveryRepository.markAllRead(requireStaff().getId(), Instant.now());
    }

    /** Someone who did not receive an email cannot tell it from one that does not exist. */
    private EmailDelivery requireDelivery(Long emailId) {
        Long me = requireStaff().getId();
        return deliveryRepository.findByEmailIdAndUserId(emailId, me)
                .orElseThrow(() -> new NotFoundException("Email not found"));
    }

    // ---- shared --------------------------------------------------------------------

    /** Emails are staff correspondence: a customer login never reads or sends one, whatever its role holds. */
    private User requireStaff() {
        User me = currentUser.require();
        if (me.getCustomerId() != null) {
            throw new AccessDeniedException("Not allowed");
        }
        return me;
    }

    private void requireViewOf(EmailDtos.TargetType type) {
        String privilege = type == EmailDtos.TargetType.CUSTOMER ? Privileges.CUSTOMER_VIEW : Privileges.INVOICE_VIEW;
        if (!currentUser.has(privilege)) {
            throw new AccessDeniedException("Not allowed");
        }
    }

    private User requireActiveStaff(Long userId, String as) {
        User u = userId == null ? null : userRepository.findById(userId).orElse(null);
        if (u == null || !u.isActive() || u.getCustomerId() != null) {
            throw new BadRequestException("The " + as + " must be an active internal user (id " + userId + ")");
        }
        return u;
    }

    private Role requireEmailRole(Long roleId) {
        Role r = roleId == null ? null : roleRepository.findById(roleId).orElse(null);
        if (r == null) {
            throw new BadRequestException("Role not found (id " + roleId + ")");
        }
        if (CUSTOMER_ROLE.equalsIgnoreCase(r.getName())) {
            throw new BadRequestException("The " + r.getName() + " role cannot send or receive emails");
        }
        return r;
    }

    static String displayName(User u) {
        return u.getFullName() != null && !u.getFullName().isBlank() ? u.getFullName() : u.getUsername();
    }

    private EmailDtos.InboxItemDto toInboxItem(EmailDelivery d) {
        return new EmailDtos.InboxItemDto(d.getEmail().getId(), d.getReadAt() != null, d.getReadAt(),
                toDto(d.getEmail()));
    }

    private EmailDtos.EmailDto toDto(Email e) {
        Invoice invoice = e.getInvoice();
        return new EmailDtos.EmailDto(
                e.getId(),
                invoice == null ? EmailDtos.TargetType.CUSTOMER : EmailDtos.TargetType.INVOICE,
                e.getCustomer().getId(), e.getCustomer().getName(),
                invoice == null ? null : invoice.getId(),
                invoice == null ? null : invoice.getInvoiceNumber(),
                new EmailDtos.SenderDto(e.getFromType(), e.getFromUserId(), e.getFromRoleId(),
                        e.getFromName(), e.getFromAddress()),
                e.getRecipients().stream().map(r -> new EmailDtos.RecipientDto(
                        r.getType(), r.getUserId(), r.getRoleId(), r.getName(), r.getAddress(),
                        r.getMembers().stream()
                                .map(m -> new EmailDtos.MemberDto(m.getUserId(), m.getName(), m.getAddress()))
                                .toList())).toList(),
                e.getSubject(), e.getBody(),
                e.getSentByUserId(), e.getSentByName(), e.getSentAt());
    }
}

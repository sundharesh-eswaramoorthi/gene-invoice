package com.geneinvoice.email;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.Emails;
import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerService;
import com.geneinvoice.email.EmailDtos.Detail;
import com.geneinvoice.email.EmailDtos.Options;
import com.geneinvoice.email.EmailDtos.RecipientDto;
import com.geneinvoice.email.EmailDtos.RecipientOption;
import com.geneinvoice.email.EmailDtos.RecordEmailSummary;
import com.geneinvoice.email.EmailDtos.SendOutcome;
import com.geneinvoice.email.EmailDtos.SendRequest;
import com.geneinvoice.email.EmailDtos.SenderOption;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.role.Role;
import com.geneinvoice.role.RoleRepository;
import com.geneinvoice.setting.AppSettingKeys;
import com.geneinvoice.setting.AppSettingRepository;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The send-time boundary for stored Emails. Everything an Email says — sender, recipients,
 * linked record — is resolved here, once, at send time, and frozen into the snapshot rows; later
 * changes to roles, members or addresses do not touch an Email already stored (AC10).
 */
@Service
@RequiredArgsConstructor
public class EmailService {

    private final EmailRepository emailRepository;
    private final EmailRecipientRepository recipientRepository;
    private final EmailRecipientReadRepository readRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final AppSettingRepository settingRepository;
    private final CustomerService customerService;
    private final InvoiceService invoiceService;
    private final CurrentUser currentUser;

    // ---- compose options ---------------------------------------------------------

    /** The From / To choices the compose dialog offers: internal users and roles. */
    @Transactional(readOnly = true)
    public Options options() {
        List<SenderOption> senders = new ArrayList<>();
        List<RecipientOption> recipients = new ArrayList<>();
        for (User u : userRepository.findInternalUsers()) {
            senders.add(new SenderOption("USER", u.getId(), display(u), Emails.normalize(u.getEmail())));
            recipients.add(new RecipientOption("USER", u.getId(), display(u), Emails.normalize(u.getEmail())));
        }
        for (Role r : roleRepository.findAll(Sort.by("name"))) {
            senders.add(new SenderOption("ROLE", r.getId(), r.getName(), Emails.normalize(r.getEmail())));
            recipients.add(new RecipientOption("ROLE", r.getId(), r.getName(), Emails.normalize(r.getEmail())));
        }
        return new Options(senders, recipients);
    }

    // ---- sending -----------------------------------------------------------------

    @Transactional
    public SendOutcome sendForCustomer(Long customerId, SendRequest req) {
        // Scoped: a customer outside the caller's book is a 404 here, as on every customer path.
        Customer customer = customerService.get(customerId);
        return create(customer, null, req);
    }

    @Transactional
    public SendOutcome sendForInvoice(Long invoiceId, SendRequest req) {
        // Scoped get, never getInternal: Email send inherits the invoice's visibility rules (AQ6).
        Invoice invoice = invoiceService.get(invoiceId);
        return create(null, invoice, req);
    }

    /** Validation a bulk run applies once up front, so a bad draft is one 400, not N failed rows. */
    public void validateDraft(SendRequest req) {
        if (req.subject() == null || req.subject().trim().isEmpty()) {
            throw new BadRequestException("Subject is required");
        }
        if (req.subject().trim().length() > FieldLimits.EMAIL_SUBJECT) {
            throw new BadRequestException("Subject is too long (max " + FieldLimits.EMAIL_SUBJECT + ")");
        }
        if (req.body() != null && req.body().length() > FieldLimits.EMAIL_BODY) {
            throw new BadRequestException("Body is too long (max " + FieldLimits.EMAIL_BODY + ")");
        }
        boolean anyTo = req.includeCustomerAddress()
                || (req.toUserIds() != null && !req.toUserIds().isEmpty())
                || (req.toRoleIds() != null && !req.toRoleIds().isEmpty());
        if (!anyTo) {
            throw new BadRequestException("Select at least one recipient");
        }
    }

    private SendOutcome create(Customer customer, Invoice invoice, SendRequest req) {
        validateDraft(req);
        Sender sender = resolveSender(req);
        Customer rowCustomer = customer != null ? customer : invoice.getCustomer();
        List<Occurrence> occurrences = resolveRecipients(req, rowCustomer);
        if (occurrences.isEmpty()) {
            // FAIL1 / FAIL3: a row resolving to nobody records no Email; bulk counts it as
            // skipped, a single send reports this message.
            return new SendOutcome(false, null,
                    "No recipients could be resolved for this record, so no Email was created");
        }

        User sentBy = currentUser.require();
        Email email = emailRepository.save(Email.builder()
                .customer(customer)
                .invoice(invoice)
                .senderDisplay(sender.display())
                .senderAddress(sender.address())
                .subject(req.subject().trim())
                .body(normalizeBody(req.body()))
                .sentByUserId(sentBy.getId())
                .sentByDisplay(display(sentBy))
                .build());
        recipientRepository.saveAll(occurrences.stream()
                .map(o -> EmailRecipient.builder()
                        .email(email).kind(o.kind()).label(o.label())
                        .address(o.address()).roleName(o.roleName()).userId(o.userId())
                        .build())
                .toList());
        // One unread row per distinct internal recipient, however they were reached (FR15).
        Set<Long> readers = new LinkedHashSet<>();
        for (Occurrence o : occurrences) {
            if (o.userId() != null) readers.add(o.userId());
        }
        readRepository.saveAll(readers.stream()
                .map(uid -> EmailRecipientRead.builder().email(email).userId(uid).read(false).build())
                .toList());
        return new SendOutcome(true, email.getId(), null);
    }

    private record Sender(String display, String address) {}

    private Sender resolveSender(SendRequest req) {
        boolean fromUser = req.fromUserId() != null;
        boolean fromRole = req.fromRoleId() != null;
        if (fromUser == fromRole) {
            throw new BadRequestException("From must name exactly one internal user or role");
        }
        String address;
        if (fromRole) {
            Role role = roleRepository.findById(req.fromRoleId())
                    .orElseThrow(() -> new NotFoundException("Role not found"));
            // FAIL2: the displayed sender stays the role; only the address falls back.
            address = Emails.normalize(role.getEmail());
            if (address == null) address = requireAdminFallback();
            return new Sender(role.getName(), address);
        }
        User user = userRepository.findById(req.fromUserId())
                .orElseThrow(() -> new NotFoundException("User not found"));
        if (user.getCustomerId() != null) {
            throw new BadRequestException("From must name an internal user or a role");
        }
        address = Emails.normalize(user.getEmail());
        // Settled clarification: a named internal user without an address uses the same
        // application-wide fallback a role without one would.
        if (address == null) address = requireAdminFallback();
        return new Sender(display(user), address);
    }

    private String requireAdminFallback() {
        String fallback = adminEmail();
        if (fallback == null) {
            throw new BadRequestException(
                    "No sender address is available: an administrator must set the "
                            + "application-wide admin email under Settings first");
        }
        return fallback;
    }

    /** The configured application-wide admin email, or null on an unconfigured installation. */
    public String adminEmail() {
        return settingRepository.findById(AppSettingKeys.ADMIN_EMAIL)
                .map(s -> Emails.normalize(s.getValue()))
                .orElse(null);
    }

    private record Occurrence(EmailRecipientKind kind, String label, String address,
                              String roleName, Long userId) {}

    /** Role memberships, users and the row's Customer address, all as they stand right now. */
    private List<Occurrence> resolveRecipients(SendRequest req, Customer rowCustomer) {
        List<Occurrence> out = new ArrayList<>();

        Set<Long> reachedViaRole = new HashSet<>();
        for (Role role : selectedRoles(req.toRoleIds())) {
            for (User member : userRepository.findByRoleId(role.getId())) {
                if (member.getCustomerId() != null) continue;   // customer logins have no Inbox
                // One occurrence per selected role, even for users in several of them (EDGE2).
                out.add(new Occurrence(EmailRecipientKind.ROLE, display(member),
                        Emails.normalize(member.getEmail()), role.getName(), member.getId()));
                reachedViaRole.add(member.getId());
            }
        }
        if (req.toUserIds() != null) {
            for (Long id : new LinkedHashSet<>(req.toUserIds())) {
                User user = userRepository.findById(id)
                        .orElseThrow(() -> new NotFoundException("User not found: " + id));
                // EDGE1: a user also reached through a selected role appears only once (the
                // role-labelled occurrence); a customer-scoped account cannot hold Inbox state.
                if (user.getCustomerId() != null || reachedViaRole.contains(user.getId())) continue;
                out.add(new Occurrence(EmailRecipientKind.DIRECT, display(user),
                        Emails.normalize(user.getEmail()), null, user.getId()));
            }
        }
        if (req.includeCustomerAddress() && rowCustomer != null) {
            String address = Emails.normalize(rowCustomer.getEmail());
            // FAIL4: an address missing at send time is simply omitted; zero-recipient rules
            // apply to whatever remains.
            if (address != null) {
                out.add(new Occurrence(EmailRecipientKind.CUSTOMER_ADDRESS, address, address, null, null));
            }
        }
        return out;
    }

    private List<Role> selectedRoles(List<Long> roleIds) {
        Map<Long, Role> roles = new LinkedHashMap<>();
        if (roleIds == null) return List.of();
        for (Long id : new LinkedHashSet<>(roleIds)) {
            Role role = roleRepository.findById(id)
                    .orElseThrow(() -> new NotFoundException("Role not found: " + id));
            roles.put(role.getId(), role);
        }
        return List.copyOf(roles.values());
    }

    private static String display(User u) {
        String full = u.getFullName();
        return full == null || full.isBlank() ? u.getUsername() : full;
    }

    /** Body may be absent or empty (FR9); both are stored as no-body rather than whitespace. */
    private static String normalizeBody(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : raw;
    }

    // ---- record-context projections ------------------------------------------------

    /** The Customer tab's roll-up: the customer's own Emails plus its invoices' Emails,
     *  newest first, with invoice numbers on the invoice-linked ones (FR12). */
    @Transactional(readOnly = true)
    public List<RecordEmailSummary> forCustomer(Long customerId) {
        customerService.get(customerId);    // scoped parent validation
        List<Email> all = new ArrayList<>(emailRepository.findByCustomer_IdOrderBySentAtDescIdDesc(customerId));
        all.addAll(emailRepository.findByCustomerInvoices(customerId));
        all.sort(Comparator.comparing(Email::getSentAt).reversed()
                .thenComparing(Comparator.comparing(Email::getId).reversed()));
        return summarize(all);
    }

    /** The Invoice tab stays invoice-only: an Email appears exactly when linked to it (FR13). */
    @Transactional(readOnly = true)
    public List<RecordEmailSummary> forInvoice(Long invoiceId) {
        invoiceService.get(invoiceId);      // scoped parent validation
        return summarize(emailRepository.findByInvoice_IdOrderBySentAtDescIdDesc(invoiceId));
    }

    private List<RecordEmailSummary> summarize(List<Email> emails) {
        if (emails.isEmpty()) return List.of();
        List<Long> ids = emails.stream().map(Email::getId).toList();
        Map<Long, Long> counts = recipientRepository.findByEmail_IdIn(ids).stream()
                .collect(Collectors.groupingBy(r -> r.getEmail().getId(), Collectors.counting()));
        return emails.stream()
                .map(e -> new RecordEmailSummary(e.getId(), e.getSubject(), e.getSenderDisplay(),
                        e.getSentAt(), e.getInvoice() != null ? "INVOICE" : "CUSTOMER",
                        e.getInvoice() != null ? e.getInvoice().getInvoiceNumber() : null,
                        counts.getOrDefault(e.getId(), 0L).intValue()))
                .toList();
    }

    // ---- reading -------------------------------------------------------------------

    /** The immutable detail view. Opening marks the Email read for the logged-in recipient when
     *  they actually are one; everyone else's state is untouched (FR16). */
    @Transactional
    public Detail open(Long emailId) {
        Email e = emailRepository.findById(emailId)
                .orElseThrow(() -> new NotFoundException("Email not found"));
        Long me = currentUser.require().getId();
        Boolean read = null;
        Optional<EmailRecipientRead> row = readRepository.findByEmail_IdAndUserId(emailId, me);
        if (row.isPresent()) {
            if (!row.get().isRead()) {
                row.get().setRead(true);
                readRepository.save(row.get());
            }
            read = true;
        }
        List<RecipientDto> recipients = recipientRepository.findByEmail_Id(emailId).stream()
                .map(RecipientDto::from).toList();
        return new Detail(e.getId(), e.getSubject(), e.getBody(), e.getSenderDisplay(),
                e.getSenderAddress(), e.getSentAt(), e.getSentByDisplay(),
                e.getCustomer() != null ? e.getCustomer().getId() : null,
                e.getInvoice() != null ? e.getInvoice().getId() : null,
                e.getInvoice() != null ? e.getInvoice().getInvoiceNumber() : null,
                recipients, read);
    }

    /** Explicit single mark-read; idempotent, and a no-op for an Email the caller never got. */
    @Transactional
    public void markRead(Long emailId) {
        readRepository.findByEmail_IdAndUserId(emailId, currentUser.require().getId())
                .filter(r -> !r.isRead())
                .ifPresent(r -> {
                    r.setRead(true);
                    readRepository.save(r);
                });
    }

    /** Every unread row of the caller, across all pages, in one owner-scoped update (EDGE3). */
    @Transactional
    public int markAllReadForCaller() {
        return readRepository.markAllRead(currentUser.require().getId());
    }
}

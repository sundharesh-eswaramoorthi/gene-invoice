package com.geneinvoice.email;

import com.geneinvoice.common.Emails;
import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.email.transport.InboundHint;
import com.geneinvoice.email.transport.IncomingMail;
import com.geneinvoice.email.transport.IncomingMailHandler;
import com.geneinvoice.email.transport.MailAddress;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailInboundService implements IncomingMailHandler {

    private final EmailRepository emailRepository;
    private final EmailRecipientRepository recipientRepository;
    private final EmailDirectory directory;
    private final UserRepository userRepository;
    private final TransactionTemplate transactions;

    private record Link(EmailEntityType type, Long id, String label, Email repliedTo) {}

    @Override
    public Optional<Long> handle(IncomingMail received, InboundHint hint) {
        if (received.providerMessageId() == null || received.providerMessageId().isBlank()) {
            log.warn("Ignoring received mail without a provider message id");
            return Optional.empty();
        }
        IncomingMail mail = storable(received);
        try {
            return transactions.execute(status -> save(mail, hint));
        } catch (DataIntegrityViolationException e) {
            if (emailRepository.existsByProviderMessageId(mail.providerMessageId())) {
                return known(mail.providerMessageId());
            }
            log.warn("Received mail {} could not be saved: {}", mail.providerMessageId(),
                    e.getMostSpecificCause().getMessage());
            return Optional.empty();
        }
    }

    private static IncomingMail storable(IncomingMail mail) {
        return new IncomingMail(mail.providerMessageId(), mail.providerThreadId(),
                EmailText.storable(mail.rfcMessageId()), EmailText.storable(mail.inReplyTo()),
                mail.references().stream().map(EmailText::storable).toList(),
                storable(mail.from()),
                mail.to().stream().map(EmailInboundService::storable).toList(),
                mail.cc().stream().map(EmailInboundService::storable).toList(),
                EmailText.storable(mail.subject()), EmailText.storable(mail.body()), mail.receivedAt());
    }

    private static MailAddress storable(MailAddress address) {
        return address == null ? null
                : new MailAddress(EmailText.storable(address.name()), EmailText.storable(address.address()));
    }

    private Optional<Long> known(String providerMessageId) {
        return emailRepository.findByProviderMessageId(providerMessageId)
                .filter(e -> e.getDirection() == EmailDirection.INBOUND)
                .map(Email::getId);
    }

    private Optional<Long> save(IncomingMail mail, InboundHint hint) {
        Optional<Email> existing = emailRepository.findByProviderMessageId(mail.providerMessageId());
        if (existing.isPresent()) {
            return existing.filter(e -> e.getDirection() == EmailDirection.INBOUND).map(Email::getId);
        }
        if (recipientRepository.existsByProviderMessageId(mail.providerMessageId())) return Optional.empty();
        String messageId = messageId(mail.rfcMessageId());
        if (messageId != null && (emailRepository.existsByRfcMessageIdAndDirection(messageId, EmailDirection.OUTBOUND)
                || recipientRepository.existsByRfcMessageId(messageId))) {
            return Optional.empty();
        }
        Link link = link(mail, hint);
        if (link == null) return Optional.empty();

        MailAddress fromHeader = mail.from() == null ? new MailAddress(null, null) : mail.from();
        String fromAddress = Emails.normalize(fromHeader.address());
        Email.EmailBuilder email = Email.builder()
                .entityType(link.type())
                .entityId(link.id())
                .entityLabel(EmailText.fit(link.label(), Email.LABEL_MAX))
                .direction(EmailDirection.INBOUND)
                .status(EmailStatus.RECEIVED)
                .subject(EmailText.fit(EmailText.oneLine(mail.subject()), FieldLimits.EMAIL_SUBJECT))
                .body(EmailText.fit(mail.body() == null ? "" : mail.body(), FieldLimits.EMAIL_BODY))
                .fromAddress(EmailText.fit(fromAddress, Email.ADDRESS_MAX))
                .providerMessageId(mail.providerMessageId())
                .providerThreadId(mail.providerThreadId())
                .rfcMessageId(EmailText.fit(messageId, Email.HEADER_ID_MAX))
                .inReplyTo(EmailText.fit(messageId(mail.inReplyTo()), Email.HEADER_ID_MAX))
                .occurredAt(mail.receivedAt() == null ? Instant.now() : mail.receivedAt());
        describeSender(email, fromHeader, fromAddress);

        RecipientSet recipients = new RecipientSet();
        for (MailAddress to : mail.to()) addRecipient(recipients, RecipientField.TO, to, hint, link.repliedTo());
        for (MailAddress cc : mail.cc()) addRecipient(recipients, RecipientField.CC, cc, hint, link.repliedTo());
        if (hint != null) {
            // It arrived in their mailbox, so it is theirs to read, however it was addressed — Bcc included (E8).
            String mailbox = Emails.normalize(hint.mailboxAddress());
            addMailboxOwner(recipients, RecipientField.TO, hint, link.repliedTo(), mailbox, mailbox);
        }

        Email saved = emailRepository.save(email.build());
        recipientRepository.saveAll(recipients.entries().stream().map(r -> EmailRecipient.builder()
                .email(saved)
                .field(r.field())
                .userId(r.userId())
                .customerId(r.customerId())
                .name(EmailText.fit(r.name(), Email.NAME_MAX))
                .address(EmailText.fit(r.address(), Email.ADDRESS_MAX))
                .internal(r.internal())
                .sources(String.join(",", r.sources()))
                .build()).toList());
        return Optional.of(saved.getId());
    }

    private Link link(IncomingMail mail, InboundHint hint) {
        Map<Long, Email> related = new LinkedHashMap<>();
        Email anchor = hint == null ? null : CopyRef.parse(hint.repliedToExternalId())
                .flatMap(ref -> emailRepository.findById(ref.emailId()))
                .filter(e -> e.getDirection() == EmailDirection.OUTBOUND)
                .orElse(null);
        if (anchor != null) related.put(anchor.getId(), anchor);

        String thread = mail.providerThreadId();
        if (anchor == null && thread != null && !thread.isBlank()) {
            emailRepository.findByProviderThreadIdOrderByOccurredAtDescIdDesc(thread)
                    .forEach(e -> related.putIfAbsent(e.getId(), e));
            recipientRepository.findCopiesInThread(thread).forEach(r -> related.putIfAbsent(r.getEmail().getId(), r.getEmail()));
            anchor = related.values().stream().max(NEWEST_LAST).orElse(null);
        }
        if (anchor == null) {
            List<String> quoted = new ArrayList<>();
            if (messageId(mail.inReplyTo()) != null) quoted.add(messageId(mail.inReplyTo()));
            List<String> references = new ArrayList<>(mail.references());
            Collections.reverse(references);
            references.stream().map(EmailInboundService::messageId).filter(Objects::nonNull).forEach(quoted::add);
            Set<String> ids = new LinkedHashSet<>(quoted);
            if (!ids.isEmpty()) {
                Map<String, Email> byMessageId = new LinkedHashMap<>();
                emailRepository.findByRfcMessageIdInOrderByOccurredAtDescIdDesc(ids)
                        .forEach(e -> byMessageId.putIfAbsent(e.getRfcMessageId(), e));
                recipientRepository.findCopiesByRfcMessageIdIn(ids)
                        .forEach(r -> byMessageId.putIfAbsent(r.getRfcMessageId(), r.getEmail()));
                anchor = ids.stream().map(byMessageId::get).filter(Objects::nonNull).findFirst().orElse(null);
                byMessageId.values().forEach(e -> related.putIfAbsent(e.getId(), e));
            }
        }
        if (anchor == null) return null;
        Email repliedTo = related.values().stream()
                .filter(e -> e.getDirection() == EmailDirection.OUTBOUND)
                .max(NEWEST_LAST)
                .orElse(null);
        return new Link(anchor.getEntityType(), anchor.getEntityId(), anchor.getEntityLabel(), repliedTo);
    }

    private static final Comparator<Email> NEWEST_LAST =
            Comparator.comparing(Email::getOccurredAt).thenComparing(Email::getId);

    /**
     * The user an address is: their email in Users, else the Gmail they connected to send from (M2),
     * which may differ — staff replying from that Gmail are still staff, and masked to customers.
     */
    private Optional<User> userAt(String address) {
        return directory.userByAddress(address).or(() -> directory.userByGmail(address));
    }

    private void describeSender(Email.EmailBuilder email, MailAddress header, String address) {
        String headerName = header.name() == null || header.name().isBlank() ? null : header.name().trim();
        Optional<User> user = userAt(address);
        if (user.isPresent()) {
            User u = user.get();
            email.fromUserId(u.getId())
                    .fromName(EmailText.fit(EmailText.nameOf(u), Email.NAME_MAX))
                    .fromCustomerId(u.getCustomerId())
                    .fromInternal(u.getCustomerId() == null);
            return;
        }
        Optional<Customer> customer = directory.customerByAddress(address);
        String name = headerName != null ? headerName
                : customer.map(Customer::getName).orElse(address == null ? "Unknown sender" : address);
        email.fromName(EmailText.fit(name, Email.NAME_MAX))
                .fromCustomerId(customer.map(Customer::getId).orElse(null))
                .fromInternal(false);
    }

    /**
     * One To or Cc address. The mailbox the reply arrived in stands for its owner, who sent the email
     * being answered (E8), so the reply lands in their Inbox.
     */
    private void addRecipient(RecipientSet recipients, RecipientField field, MailAddress header, InboundHint hint,
                              Email repliedTo) {
        String address = Emails.normalize(header.address());
        if (address == null) return;
        String headerName = header.name() == null || header.name().isBlank() ? address : header.name().trim();

        if (hint != null && isMailbox(address, hint.mailboxAddress())) {
            addMailboxOwner(recipients, field, hint, repliedTo, headerName, address);
            return;
        }
        Optional<User> user = userAt(address);
        if (user.isPresent()) {
            User u = user.get();
            recipients.add(field, u.getId(), u.getCustomerId(), EmailText.nameOf(u), address,
                    u.getCustomerId() == null, "HEADER");
            return;
        }
        Optional<Customer> customer = directory.customerByAddress(address);
        if (customer.isPresent()) {
            recipients.add(field, null, customer.get().getId(), customer.get().getName(), address, false, "CUSTOMER");
            return;
        }
        recipients.add(field, null, null, headerName, address, false, "HEADER");
    }

    private void addMailboxOwner(RecipientSet recipients, RecipientField field, InboundHint hint, Email repliedTo,
                                 String headerName, String address) {
        User owner = userRepository.findById(hint.mailboxOwnerUserId()).orElse(null);
        if (owner != null) {
            recipients.add(field, EmailTargets.Person.of(owner), "MAILBOX");
        } else if (repliedTo != null && Objects.equals(repliedTo.getFromUserId(), hint.mailboxOwnerUserId())) {
            recipients.add(field, null, repliedTo.getFromCustomerId(), repliedTo.getFromName(),
                    repliedTo.getFromAddress(), repliedTo.isFromInternal(), "MAILBOX");
        } else if (address != null) {
            recipients.add(field, null, null, headerName == null ? address : headerName, address, true, "HEADER");
        }
    }

    private static boolean isMailbox(String address, String mailbox) {
        if (mailbox == null || mailbox.isBlank()) return false;
        return EmailDirectory.withoutTag(address).equals(EmailDirectory.withoutTag(mailbox));
    }

    private static String messageId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String id = raw.trim();
        return id.startsWith("<") ? id : "<" + id + ">";
    }
}

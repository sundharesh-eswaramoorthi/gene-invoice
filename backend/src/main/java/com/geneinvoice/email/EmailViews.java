package com.geneinvoice.email;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.query.PredicateFactory;
import com.geneinvoice.email.EmailDtos.Participant;
import com.geneinvoice.email.EmailDtos.Source;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
public class EmailViews {

    private static final int SNIPPET_MAX = 160;
    static final String NOT_DELIVERED = "Could not be delivered";

    private final EmailRecipientRepository recipientRepository;
    private final UserRepository userRepository;
    private final CurrentUser currentUser;

    public record Viewer(Long userId, Long customerId, boolean canSend) {
        boolean isCustomer() {
            return customerId != null;
        }
    }

    public Viewer viewer() {
        User me = currentUser.require();
        return new Viewer(me.getId(), me.getCustomerId(), currentUser.has(Privileges.EMAIL_SEND));
    }

    // ---- which emails a customer sees (E13) ---------------------------------------------

    static PredicateFactory customerTookPart(Long customerId) {
        return (root, q, cb) -> {
            Subquery<Long> sq = q.subquery(Long.class);
            Root<EmailRecipient> r = sq.from(EmailRecipient.class);
            sq.select(cb.literal(1L)).where(
                    cb.equal(r.get("email").get("id"), root.get("id")),
                    cb.equal(r.get("customerId"), customerId));
            return cb.or(cb.equal(root.get("fromCustomerId"), customerId), cb.exists(sq));
        };
    }

    static boolean customerTookPart(Email email, List<EmailRecipient> recipients, Long customerId) {
        return customerId.equals(email.getFromCustomerId())
                || recipients.stream().anyMatch(r -> customerId.equals(r.getCustomerId()));
    }

    public List<EmailDtos.EmailDto> toDtos(List<Email> emails, Viewer viewer, boolean canSeeRecord) {
        if (emails.isEmpty()) return List.of();
        Map<Long, List<EmailRecipient>> recipients = recipientRepository
                .findByEmailIdInOrderByIdAsc(emails.stream().map(Email::getId).toList()).stream()
                .collect(Collectors.groupingBy(r -> r.getEmail().getId()));
        Map<Long, User> senders = senders(emails);
        return emails.stream().map(e -> toDto(e, recipients.getOrDefault(e.getId(), List.of()),
                viewer, canSeeRecord, senders)).toList();
    }

    public EmailDtos.EmailDto toDto(Email email, List<EmailRecipient> recipients, Viewer viewer,
                                    boolean canSeeRecord) {
        return toDto(email, recipients, viewer, canSeeRecord, senders(List.of(email)));
    }

    private EmailDtos.EmailDto toDto(Email e, List<EmailRecipient> recipients, Viewer viewer,
                                     boolean canSeeRecord, Map<Long, User> senders) {
        Boolean readByMe = recipients.stream()
                .filter(r -> r.getField() == RecipientField.TO && viewer.userId().equals(r.getUserId()))
                .findFirst().map(EmailRecipient::isRead).orElse(null);
        boolean canRetry = EmailDeliveryRollup.canRetry(e, recipients)
                && viewer.canSend() && canSeeRecord && mayRetry(e, viewer);
        List<EmailDtos.Unresolved> unresolved = e.getUnresolved() == null ? List.of()
                : Arrays.stream(e.getUnresolved().split(",")).map(String::trim).filter(t -> !t.isEmpty())
                        .map(t -> EmailAddressing.describeUnresolved(e.getEntityType(), t, e.getEntityLabel()))
                        .toList();
        Outcome outcome = outcome(e, recipients, viewer);
        return new EmailDtos.EmailDto(
                e.getId(), e.getEntityType(), e.getEntityId(), entityLabel(e), entityLink(e),
                e.getDirection(), outcome.status(), e.getSubject(), e.getBody(),
                from(e, viewer), e.getFromRole(),
                e.getFromRole() == null ? null : fromRole(e).level(),
                e.getFromRole() == null ? null : fromRole(e).label(e.getEntityType()),
                participants(e, recipients, RecipientField.TO, viewer),
                participants(e, recipients, RecipientField.CC, viewer),
                unresolved, sentBy(e, senders, viewer), deliveredFrom(e, viewer), outcome.error(), e.getAttempts(),
                e.getOccurredAt(), outcome.sentAt(), canRetry, canSeeRecord, readByMe);
    }

    private record Outcome(EmailStatus status, String error, Instant sentAt) {}

    private static Outcome outcome(Email e, List<EmailRecipient> recipients, Viewer viewer) {
        if (viewer.isCustomer()) {
            List<EmailRecipient> theirs = recipients.stream()
                    .filter(r -> r.getDeliveryStatus() != null && !r.isInternal()).toList();
            if (!theirs.isEmpty()) {
                return new Outcome(EmailDeliveryRollup.status(theirs),
                        shownError(EmailDeliveryRollup.error(theirs), e, viewer),
                        theirs.stream().map(EmailRecipient::getSentAt).filter(Objects::nonNull)
                                .min(Comparator.naturalOrder()).orElse(null));
            }
        }
        return new Outcome(e.getStatus(), shownError(e.getError(), e, viewer), e.getSentAt());
    }

    /** A customer login sends from themselves only (E13), and so retries only what they sent. */
    static boolean mayRetry(Email e, Viewer viewer) {
        return !viewer.isCustomer() || viewer.userId().equals(e.getSentByUserId());
    }

    /** The staff sender's own Gmail address, which a customer does not get (E13). */
    private static String deliveredFrom(Email e, Viewer viewer) {
        if (viewer.isCustomer() && !viewer.customerId().equals(e.getFromCustomerId())) return null;
        return e.getDeliveredFrom();
    }

    private static String shownError(String error, Email e, Viewer viewer) {
        if (error == null || !viewer.isCustomer()
                || error.equals(EmailDispatcher.NOT_CONFIGURED) || error.equals(EmailDispatcher.NO_ADDRESS)
                || error.equals(EmailDispatcher.CUSTOMER_SENDER)) {
            return error;
        }
        boolean senderIsTheirs = viewer.customerId().equals(e.getFromCustomerId());
        if (senderIsTheirs && (error.endsWith(NOT_CONNECTED_SUFFIX) || error.endsWith(RENEW_SUFFIX))) return error;
        return NOT_DELIVERED;
    }

    private static final String NOT_CONNECTED_SUFFIX = " has not connected Gmail";
    private static final String RENEW_SUFFIX = "'s Gmail connection needs to be renewed";

    private Map<Long, User> senders(List<Email> emails) {
        List<Long> ids = emails.stream().map(Email::getSentByUserId).filter(Objects::nonNull).distinct().toList();
        return ids.isEmpty() ? Map.of() : userRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private EmailDtos.SentBy sentBy(Email e, Map<Long, User> senders, Viewer viewer) {
        if (e.getSentByUserId() == null) return null;
        User u = senders.get(e.getSentByUserId());
        boolean staff = u == null || u.getCustomerId() == null;
        if (viewer.isCustomer() && staff) return new EmailDtos.SentBy(null, EmailText.TEAM);
        String name = u != null ? EmailText.nameOf(u)
                : e.getSentByUserId().equals(e.getFromUserId()) ? e.getFromName() : null;
        return new EmailDtos.SentBy(e.getSentByUserId(), name);
    }

    private List<Participant> participants(Email e, List<EmailRecipient> recipients, RecipientField field,
                                           Viewer viewer) {
        return merged(recipients.stream().filter(r -> r.getField() == field)
                .map(r -> shown(r.getName(), r.getAddress(), r.getUserId(), r.isInternal(),
                        sources(r.getSources(), e.getEntityType()), viewer, delivery(e, r, viewer)))
                .toList());
    }

    private static EmailDtos.RecipientDelivery delivery(Email e, EmailRecipient r, Viewer viewer) {
        if (e.getDirection() != EmailDirection.OUTBOUND || r.getField() != RecipientField.TO) return null;
        Instant readInApp = r.getUserId() != null && r.isRead() ? r.getReadAt() : null;
        if (r.getDeliveryStatus() == null && readInApp == null) return null;
        return new EmailDtos.RecipientDelivery(r.getDeliveryStatus(), shownError(r.getDeliveryError(), e, viewer),
                r.getSentAt(), r.getDeliveredAt(), r.isDeliveredConfirmed(), r.getMailReadAt(), r.getBouncedAt(),
                readInApp);
    }

    Participant from(Email e, Viewer viewer) {
        List<Source> sources = e.getFromRole() != null
                ? List.of(roleSource(fromRole(e), e.getEntityType()))
                : List.of(new Source(e.getDirection() == EmailDirection.INBOUND ? "HEADER" : "USER",
                        null, null, null));
        return shown(e.getFromName(), e.getFromAddress(), e.getFromUserId(), e.isFromInternal(), sources, viewer);
    }

    /** The sender role with its level; one stored before levels existed kept none, so it names none (L7). */
    private static RoleRef fromRole(Email e) {
        return new RoleRef(e.getFromRole(), e.getFromRoleLevel());
    }

    Participant sender(EmailTargets.Person person, RoleRef role, Viewer viewer, EmailEntityType type) {
        List<Source> sources = List.of(role != null ? roleSource(role, type)
                : new Source("USER", null, null, null));
        return shown(person.name(), person.address(), person.userId(), person.internal(), sources, viewer);
    }

    List<Participant> recipients(List<RecipientSet.Entry> entries, Viewer viewer, EmailEntityType type) {
        return merged(entries.stream().map(entry -> shown(entry.name(), entry.address(), entry.userId(),
                entry.internal(), entry.sources().stream().map(s -> source(s, type)).toList(), viewer)).toList());
    }

    public List<EmailDtos.InboxItemDto> toInboxItems(List<EmailRecipient> rows, Viewer viewer) {
        Map<Long, List<EmailRecipient>> recipients = !viewer.isCustomer() || rows.isEmpty() ? Map.of()
                : recipientRepository.findByEmailIdInOrderByIdAsc(
                                rows.stream().map(r -> r.getEmail().getId()).distinct().toList()).stream()
                        .collect(Collectors.groupingBy(r -> r.getEmail().getId()));
        return rows.stream().map(r -> {
            Email e = r.getEmail();
            EmailStatus status = outcome(e, recipients.getOrDefault(e.getId(), List.of()), viewer).status();
            return new EmailDtos.InboxItemDto(r.getId(), e.getId(), e.getEntityType(), e.getEntityId(),
                    entityLabel(e), entityLink(e),
                    e.getSubject(), snippet(e.getBody()), from(e, viewer), e.getDirection(), status,
                    e.getOccurredAt(), r.isRead());
        }).toList();
    }

    /** What the record is called, saying so when it is no longer there (CP-13). */
    static String entityLabel(Email e) {
        return e.entityIsDeleted() ? e.getEntityLabel() + " (deleted)" : e.getEntityLabel();
    }

    /** Where the record is, or nowhere once it has been deleted: the link led to a 404 (CP-13). */
    static String entityLink(Email e) {
        return e.entityIsDeleted() ? null : EmailTargets.link(e.getEntityType(), e.getEntityId());
    }

    private static String snippet(String body) {
        String flat = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        return EmailText.start(flat, SNIPPET_MAX);
    }

    private static Participant shown(String name, String address, Long userId, boolean internal,
                                     List<Source> sources, Viewer viewer) {
        return shown(name, address, userId, internal, sources, viewer, null);
    }

    private static Participant shown(String name, String address, Long userId, boolean internal,
                                     List<Source> sources, Viewer viewer, EmailDtos.RecipientDelivery delivery) {
        if (viewer.isCustomer() && internal) {
            String label = sources.stream().filter(s -> "ROLE".equals(s.type())).map(Source::label)
                    .filter(Objects::nonNull).findFirst().orElse(EmailText.TEAM);
            return new Participant(label, null, null, true, true, sources, null);
        }
        return new Participant(name, address, userId, internal, false, sources, delivery);
    }

    private static List<Participant> merged(List<Participant> people) {
        List<Participant> shown = new ArrayList<>();
        Map<String, Integer> maskedAt = new HashMap<>();
        for (Participant p : people) {
            Integer at = p.masked() ? maskedAt.get(p.name()) : null;
            if (at == null) {
                if (p.masked()) maskedAt.put(p.name(), shown.size());
                shown.add(p);
                continue;
            }
            Participant first = shown.get(at);
            List<Source> sources = Stream.concat(first.sources().stream(), p.sources().stream()).distinct().toList();
            shown.set(at, new Participant(first.name(), null, null, first.internal(), true, sources, null));
        }
        return shown;
    }

    static List<Source> sources(String column, EmailEntityType type) {
        if (column == null || column.isBlank()) return List.of();
        return Arrays.stream(column.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> source(s, type)).toList();
    }

    /** A stored source; one written before levels existed carries no level, so none is shown (L7). */
    private static Source source(String token, EmailEntityType type) {
        if (token.startsWith("ROLE:")) {
            return RoleRef.parseToken(token).map(role -> roleSource(role, type))
                    .orElseGet(() -> new Source("ROLE", token.substring("ROLE:".length()), null, null));
        }
        return new Source(token, null, null, null);
    }

    private static Source roleSource(RoleRef role, EmailEntityType type) {
        return new Source("ROLE", role.role().name(),
                role.level() == null ? null : role.level().name(), role.label(type));
    }
}

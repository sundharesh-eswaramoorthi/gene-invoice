package com.geneinvoice.email;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.Emails;
import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.common.GlobalExceptionHandler;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.bulk.BulkDtos;
import com.geneinvoice.common.bulk.BulkExecutor;
import com.geneinvoice.common.query.ColumnDef;
import com.geneinvoice.common.query.ColumnType;
import com.geneinvoice.common.query.PageResponse;
import com.geneinvoice.common.query.PredicateFactory;
import com.geneinvoice.common.query.TableQuery;
import com.geneinvoice.common.query.TableQueryExecutor;
import com.geneinvoice.common.query.TableSchema;
import com.geneinvoice.email.EmailDtos.EmailToken;
import com.geneinvoice.email.connection.GmailConnectionService;
import com.geneinvoice.email.connection.GmailStatus;
import com.geneinvoice.email.transport.ConnectionState;
import com.geneinvoice.email.transport.MailConnections;
import com.geneinvoice.email.transport.MailTransport;
import com.geneinvoice.email.transport.SyncResult;
import com.geneinvoice.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    public static final String BULK_ACTION = "SEND_EMAIL";
    static final String GMAIL_NOT_CONNECTED = "Gmail is not connected";
    static final String WARN_NOT_CONFIGURED =
            "Email delivery is not configured, so this email will be saved in the app but not sent.";
    static final String WARN_CUSTOMER_SENDER =
            "Email from a customer login is saved in the app and is not sent through Gmail.";
    private static final int PEOPLE_LIMIT = 20;
    private static final int MAX_UTC_OFFSET_MINUTES = 14 * 60;

    private static final TableSchema RECORD_EMAILS = TableSchema.of("emails", "occurredAt,desc",
            ColumnDef.of("id", "Id", ColumnType.NUMBER).notFilterable().build(),
            ColumnDef.of("occurredAt", "Date", ColumnType.DATE).notFilterable().build());

    private final EmailRepository emailRepository;
    private final EmailRecipientRepository recipientRepository;
    private final EmailTargets targets;
    private final EmailAddressing addressing;
    private final EmailViews views;
    private final EmailDispatcher dispatcher;
    private final EmailDirectory directory;
    private final BulkExecutor bulkExecutor;
    private final TableQueryExecutor queryExecutor;
    private final CurrentUser currentUser;
    private final MailTransport transport;
    private final MailConnections connections;
    private final GmailConnectionService gmailConnections;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    /** Subject and body as they will be saved (E16), checked the same way for one email and for a bulk send. */
    private record Content(String subject, String body) {

        static Content check(String rawSubject, String rawBody, List<EmailToken> to) {
            Map<String, String> errors = new LinkedHashMap<>();
            String subject = EmailText.oneLine(EmailText.storable(rawSubject));
            if (subject.isEmpty()) {
                errors.put("subject", "must not be blank");
            } else if (subject.length() > FieldLimits.EMAIL_SUBJECT) {
                errors.put("subject", "must be at most " + FieldLimits.EMAIL_SUBJECT + " characters");
            }
            String body = rawBody == null ? "" : EmailText.storable(rawBody);
            if (body.length() > FieldLimits.EMAIL_BODY) {
                errors.put("body", "must be at most " + FieldLimits.EMAIL_BODY + " characters");
            }
            if (to == null || to.isEmpty()) {
                errors.put("to", "Add at least one recipient");
            }
            if (!errors.isEmpty()) throw new GlobalExceptionHandler.InvalidFieldsException(errors);
            return new Content(subject, body);
        }
    }

    public EmailDtos.ContextDto context(String entityType, Long entityId, String event, Integer utcOffsetMinutes) {
        EmailEntityType type = EmailEntityType.parse(entityType);
        targets.requireTypeAccess(type);
        EmailTargets.Event happened = event == null || event.isBlank() ? null : EmailTargets.Event.parse(event);
        ZoneId zone = readerZone(utcOffsetMinutes);
        User me = currentUser.require();
        boolean restricted = me.getCustomerId() != null;
        EmailTargets.Target target = entityId == null ? null : targets.load(type, entityId);

        List<RoleRef> offered = target == null ? targets.rolesOffered(type) : targets.rolesOffered(target);
        EmailTargets.Person self = EmailTargets.Person.of(me);
        Map<Long, GmailStatus> gmail = gmailConnections.statuses(Stream.concat(Stream.of(self),
                        target == null || restricted ? Stream.<EmailTargets.Person>empty()
                                : offered.stream().flatMap(role -> target.holders(role).stream()))
                .filter(EmailTargets.Person::internal).map(EmailTargets.Person::userId).toList());
        List<EmailDtos.RoleOption> roles = offered.stream().map(role -> {
            String label = role.role().label();
            String levelLabel = role.levelLabel(type);
            String groupLabel = role.groupLabel(type);
            if (target == null) {
                return new EmailDtos.RoleOption(role.role(), label, role.level(), levelLabel, groupLabel,
                        null, List.of(), null);
            }
            boolean resolved = !target.holders(role).isEmpty();
            if (restricted) {
                return new EmailDtos.RoleOption(role.role(), label, role.level(), levelLabel, groupLabel,
                        resolved, List.of(), null);
            }
            return new EmailDtos.RoleOption(role.role(), label, role.level(), levelLabel, groupLabel, resolved,
                    target.holders(role).stream().map(p -> personDto(p, gmail)).toList(),
                    target.sender(role).map(p -> personDto(p, gmail)).orElse(null));
        }).toList();

        EmailDtos.CustomerEmails customerEmails = target == null
                ? new EmailDtos.CustomerEmails(targets.mayHaveCustomer(type), List.of())
                : new EmailDtos.CustomerEmails(target.customerId() != null, distinctAddresses(target));

        EmailDtos.Suggestion suggestion = target == null || happened == null
                ? null : targets.suggest(target, happened, zone).orElse(null);

        return new EmailDtos.ContextDto(type, entityId,
                target == null ? null : target.label(), target == null ? null : target.link(),
                new EmailDtos.Delivery(transport.isConfigured()),
                new EmailDtos.Sender(restricted, personDto(self, gmail)),
                roles, customerEmails, suggestion);
    }

    private static ZoneId readerZone(Integer utcOffsetMinutes) {
        if (utcOffsetMinutes == null) return ZoneId.systemDefault();
        if (utcOffsetMinutes < -MAX_UTC_OFFSET_MINUTES || utcOffsetMinutes > MAX_UTC_OFFSET_MINUTES) {
            throw new BadRequestException("utcOffsetMinutes must be between -" + MAX_UTC_OFFSET_MINUTES
                    + " and " + MAX_UTC_OFFSET_MINUTES);
        }
        return ZoneOffset.ofTotalSeconds(utcOffsetMinutes * 60);
    }

    private static EmailDtos.PersonDto personDto(EmailTargets.Person p, Map<Long, GmailStatus> gmail) {
        GmailStatus status = p.internal() ? gmail.getOrDefault(p.userId(), GmailStatus.NOT_CONNECTED)
                : GmailStatus.NOT_CONNECTED;
        return new EmailDtos.PersonDto(p.userId(), p.name(), p.address(), status);
    }

    private static List<EmailDtos.CustomerAddress> distinctAddresses(EmailTargets.Target target) {
        Set<String> seen = new LinkedHashSet<>();
        return target.customerEmails().stream()
                .filter(p -> seen.add(p.address().toLowerCase(Locale.ROOT)))
                .map(p -> new EmailDtos.CustomerAddress(p.name(), p.address()))
                .toList();
    }

    /** Internal people to add by name; a customer login may not address staff by name (E13). */
    public List<EmailDtos.PeopleDto> people(String q) {
        if (currentUser.isCustomer()) throw new AccessDeniedException("Not allowed");
        List<User> found = directory.searchPeople(q, PEOPLE_LIMIT);
        Map<Long, GmailStatus> gmail = gmailConnections.statuses(found.stream().map(User::getId).toList());
        return found.stream()
                .map(u -> new EmailDtos.PeopleDto(u.getId(), EmailText.nameOf(u), u.getUsername(),
                        Emails.normalize(u.getEmail()), gmail.getOrDefault(u.getId(), GmailStatus.NOT_CONNECTED)))
                .toList();
    }

    public EmailDtos.PreviewDto preview(EmailDtos.SendEmailRequest req) {
        EmailTargets.Target target = targets.load(EmailEntityType.parse(req.entityType()), requireId(req.entityId()));
        EmailAddressing.Resolution resolution = addressing.resolve(
                addressing.plan(target, req.from(), req.to()), target);
        EmailViews.Viewer viewer = views.viewer();
        return new EmailDtos.PreviewDto(
                resolution.from() == null ? null
                        : views.sender(resolution.from(), resolution.fromRole(), viewer, target.type()),
                views.recipients(resolution.recipients(), viewer, target.type()),
                resolution.unresolved(), resolution.problems(), warnings(resolution.from()));
    }

    /**
     * Why the email would be saved but not sent (M5): no mail service, or a sender who cannot send
     * from their own Gmail. They do not stop Send, as the email is saved and can be retried.
     */
    private List<String> warnings(EmailTargets.Person sender) {
        if (!transport.isConfigured()) return List.of(WARN_NOT_CONFIGURED);
        if (sender == null) return List.of();
        if (!sender.internal()) return List.of(WARN_CUSTOMER_SENDER);
        GmailStatus gmail = gmailConnections.statuses(List.of(sender.userId()))
                .getOrDefault(sender.userId(), GmailStatus.NOT_CONNECTED);
        return switch (gmail) {
            case CONNECTED -> List.of();
            case NOT_CONNECTED -> List.of(sender.name()
                    + " has not connected Gmail, so this email will be saved in the app but not sent.");
            case NEEDS_RECONNECT -> List.of(sender.name()
                    + "'s Gmail connection needs to be renewed, so this email will be saved in the app but not sent.");
        };
    }

    public EmailDtos.EmailDto send(EmailDtos.SendEmailRequest req) {
        EmailTargets.Target target = targets.load(EmailEntityType.parse(req.entityType()), requireId(req.entityId()));
        Content content = Content.check(req.subject(), req.body(), req.to());
        EmailAddressing.Resolution resolution = addressing.resolve(
                addressing.plan(target, req.from(), req.to()), target);
        resolution.problem().ifPresent(problem -> {
            throw new BadRequestException(problem);
        });
        Long id = transactions.execute(status -> save(target, content, resolution, null));
        dispatcher.dispatch(id);
        return get(id);
    }

    public BulkDtos.BulkResult bulk(BulkDtos.BulkRequest req) {
        if (!BULK_ACTION.equals(req.action())) {
            throw new BadRequestException(
                    "Unknown bulk action: " + req.action() + " (expected one of " + List.of(BULK_ACTION) + ")");
        }
        EmailEntityType type = EmailEntityType.parse(req.stringParam("entityType"));
        targets.requireTypeAccess(type);
        EmailToken from = param(req, "from", new TypeReference<EmailToken>() {});
        List<EmailToken> to = param(req, "to", new TypeReference<List<EmailToken>>() {});
        Content content = Content.check(req.stringParam("subject"), req.stringParam("body"), to);
        EmailAddressing.Plan plan = addressing.plan(type, from, to);

        List<Long> ids = targets.bulkIds(type, req);
        boolean truncated = req.allMatching() && ids.size() >= TableQueryExecutor.BULK_ID_LIMIT;
        String batchId = UUID.randomUUID().toString();
        Map<Long, Long> emailByRecord = new HashMap<>();
        BulkDtos.BulkResult result = bulkExecutor.run(req, ids, truncated, id -> {
            EmailTargets.Target target = targets.load(type, id);
            EmailAddressing.Resolution resolution = addressing.resolve(plan, target);
            resolution.problem().ifPresent(problem -> {
                throw new BulkExecutor.IneligibleException(problem);
            });
            emailByRecord.put(id, save(target, content, resolution, batchId));
        });
        dispatcher.dispatchAll(result.succeeded().stream().map(emailByRecord::get).filter(Objects::nonNull).toList());
        return result;
    }

    private Long save(EmailTargets.Target target, Content content, EmailAddressing.Resolution resolution,
                      String batchId) {
        EmailTargets.Person from = resolution.from();
        Email email = emailRepository.save(Email.builder()
                .entityType(target.type())
                .entityId(target.id())
                .entityLabel(target.label())
                .direction(EmailDirection.OUTBOUND)
                .status(EmailStatus.QUEUED)
                .subject(content.subject())
                .body(content.body())
                .fromUserId(from.userId())
                .fromRole(resolution.fromRole() == null ? null : resolution.fromRole().role())
                .fromRoleLevel(resolution.fromRole() == null ? null : resolution.fromRole().level())
                .fromName(EmailText.fit(from.name(), Email.NAME_MAX))
                .fromAddress(from.address())
                .fromCustomerId(from.customerId())
                .fromInternal(from.internal())
                .sentByUserId(currentUser.require().getId())
                .unresolved(EmailText.fit(resolution.unresolvedTokens(), Email.UNRESOLVED_MAX))
                .batchId(batchId)
                .build());
        recipientRepository.saveAll(resolution.recipients().stream().map(r -> EmailRecipient.builder()
                .email(email)
                .field(r.field())
                .userId(r.userId())
                .customerId(r.customerId())
                .name(EmailText.fit(r.name(), Email.NAME_MAX))
                .address(r.address())
                .internal(r.internal())
                .sources(EmailText.fit(String.join(",", r.sources()), EmailRecipient.SOURCES_MAX))
                // One copy per To recipient with an address, each tracked on its own (M6).
                .deliveryStatus(r.field() == RecipientField.TO && r.address() != null
                        ? RecipientDeliveryStatus.QUEUED : null)
                .build()).toList());
        return email.getId();
    }

    public EmailDtos.EmailDto retry(Long id) {
        Email email = emailRepository.findById(id).orElseThrow(() -> new NotFoundException("Email not found"));
        targets.requireVisible(email.getEntityType(), email.getEntityId());
        EmailViews.Viewer viewer = views.viewer();
        if (viewer.isCustomer() && !EmailViews.customerTookPart(email,
                recipientRepository.findByEmailIdOrderByIdAsc(id), viewer.customerId())) {
            throw new NotFoundException("Email not found");
        }
        if (!EmailViews.mayRetry(email, viewer)) throw new AccessDeniedException("Not allowed");
        Boolean requeued = transactions.execute(status -> {
            Email locked = emailRepository.findByIdForUpdate(id).orElseThrow();
            List<EmailRecipient> recipients = recipientRepository.findByEmailIdOrderByIdAsc(id);
            if (!EmailDeliveryRollup.canRetry(locked, recipients)) return false;
            EmailDeliveryRollup.requeue(locked, recipients);
            recipientRepository.saveAll(recipients);
            emailRepository.save(locked);
            return true;
        });
        if (!Boolean.TRUE.equals(requeued)) {
            throw new BadRequestException("Only failed or unsent email can be retried");
        }
        dispatcher.dispatch(id);
        return get(id);
    }

    /** A record's emails, newest first; a customer sees only those their customer took part in (E13). */
    public PageResponse<EmailDtos.EmailDto> list(String entityType, Long entityId, Integer page, Integer size) {
        EmailEntityType type = EmailEntityType.parse(entityType);
        targets.requireVisible(type, entityId);
        TableQuery query = TableQuery.parse(RECORD_EMAILS, page, size, null, List.of());
        EmailViews.Viewer viewer = views.viewer();
        List<PredicateFactory> scope = new ArrayList<>(List.of(
                (root, q, cb) -> cb.equal(root.get("entityType"), type),
                (root, q, cb) -> cb.equal(root.get("entityId"), entityId)));
        if (viewer.isCustomer()) scope.add(EmailViews.customerTookPart(viewer.customerId()));
        var result = queryExecutor.run(Email.class, RECORD_EMAILS, query, scope, List.of());
        return PageResponse.of(views.toDtos(result.content(), viewer, true), query, result.total(), List.of());
    }

    public EmailDtos.EmailDto get(Long id) {
        Email email = emailRepository.findById(id).orElseThrow(() -> new NotFoundException("Email not found"));
        List<EmailRecipient> recipients = recipientRepository.findByEmailIdOrderByIdAsc(id);
        EmailViews.Viewer viewer = views.viewer();
        boolean party = viewer.userId().equals(email.getFromUserId())
                || viewer.userId().equals(email.getSentByUserId())
                || recipients.stream().anyMatch(r -> viewer.userId().equals(r.getUserId()));

        RuntimeException refused = null;
        try {
            targets.requireVisible(email.getEntityType(), email.getEntityId());
        } catch (AccessDeniedException | NotFoundException e) {
            refused = e;
        }
        if (!party && refused != null) throw refused;
        if (viewer.isCustomer() && !EmailViews.customerTookPart(email, recipients, viewer.customerId())) {
            throw new NotFoundException("Email not found");
        }
        return views.toDto(email, recipients, viewer, refused == null);
    }

    public EmailDtos.DeliveryStatusDto delivery() {
        User me = currentUser.require();
        if (me.getCustomerId() != null) return new EmailDtos.DeliveryStatusDto(transport.isConfigured(), null);
        return new EmailDtos.DeliveryStatusDto(transport.isConfigured(), gmailConnections.deliveryOf(me.getId()));
    }

    public EmailDtos.SyncResultDto sync() {
        if (currentUser.isCustomer()) throw new AccessDeniedException("Not allowed");
        if (!transport.isConfigured()) return new EmailDtos.SyncResultDto(false, 0, 0, EmailDispatcher.NOT_CONFIGURED);
        Long me = currentUser.require().getId();
        SyncResult result;
        try {
            result = connections.syncNow(me);
        } catch (RuntimeException e) {
            log.warn("Mail sync failed", e);
            return new EmailDtos.SyncResultDto(true, 0, 0, "Sync failed: " + e.getMessage());
        }
        refreshConnection(me);
        return new EmailDtos.SyncResultDto(result.enabled(), result.fetched(), result.imported(),
                result.enabled() || result.error() != null ? result.error() : GMAIL_NOT_CONNECTED);
    }

    private void refreshConnection(Long userId) {
        try {
            Optional<ConnectionState> state = connections.connection(userId);
            if (state.isPresent()) {
                gmailConnections.mirror(userId, state.get());
            } else {
                gmailConnections.forget(userId);
            }
        } catch (RuntimeException e) {
            log.debug("Could not refresh the Gmail connection of user {}: {}", userId, e.getMessage());
        }
    }

    private static Long requireId(Long entityId) {
        if (entityId == null) throw new BadRequestException("entityId is required");
        return entityId;
    }

    private <T> T param(BulkDtos.BulkRequest req, String key, TypeReference<T> type) {
        Object raw = req.params() == null ? null : req.params().get(key);
        if (raw == null) return null;
        try {
            return objectMapper.convertValue(raw, type);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("params." + key + " is not valid");
        }
    }
}

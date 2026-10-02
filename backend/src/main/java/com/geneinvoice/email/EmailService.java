package com.geneinvoice.email;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geneinvoice.assignee.VisibilityProbe;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.automation.action.RecordContexts;
import com.geneinvoice.automation.placeholder.PlaceholderCatalogue;
import com.geneinvoice.automation.placeholder.PlaceholderRenderer;
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
import com.geneinvoice.common.scope.AsOf;
import com.geneinvoice.common.scope.AsOfPoint;
import com.geneinvoice.common.scope.ViewScopeHolder;
import com.geneinvoice.email.EmailDtos.EmailToken;
import com.geneinvoice.email.connection.GmailConnectionService;
import com.geneinvoice.email.connection.GmailStatus;
import com.geneinvoice.email.transport.ConnectionState;
import com.geneinvoice.email.transport.MailConnections;
import com.geneinvoice.email.transport.MailTransport;
import com.geneinvoice.email.transport.SyncResult;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
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
    private final VisibilityProbe visibilityProbe;
    private final UserRepository userRepository;
    private final RecordContexts recordContexts;

    /** An email a rule would send, resolved as sending it would be (§5.10.5): why it could not go, and what to know. */
    public record RenderedPreview(EmailDtos.PreviewDto email, List<String> problems, List<String> warnings) {}

    /**
     * Who an email a rule sends would come from and go to, once everyone who cannot see the record is left out.
     * {@code noRecipients}: nothing stops it but that nobody is left to write to.
     */
    private record AutomationResolution(EmailTargets.Target target, EmailAddressing.Resolution resolution,
                                        List<String> problems, List<String> warnings, boolean noRecipients) {}

    /**
     * An email a rule sends could not be queued, and why (§5.10.5). With {@code noRecipients} nothing stopped it but
     * that nobody was left to write to, and the rule's action is skipped rather than failed.
     */
    public static class NotQueued extends RuntimeException {

        private final boolean noRecipients;

        public NotQueued(String message, boolean noRecipients) {
            super(message);
            this.noRecipients = noRecipients;
        }

        public boolean noRecipients() {
            return noRecipients;
        }
    }

    /** An email a rule queued, to be sent once the rule's action has committed; and what is worth saying about it. */
    public record Queued(Long emailId, List<String> warnings) {}

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
        EmailTargets.Target target = targets.loadForSend(EmailEntityType.parse(req.entityType()),
                requireId(req.entityId()));
        RecordContexts.EmailTexts texts = placeholders(target, req.subject(), req.body());
        EmailAddressing.Resolution resolution = addressing.resolve(
                addressing.plan(target, req.from(), req.to()), target);
        EmailViews.Viewer viewer = views.viewer();
        List<String> warnings = new ArrayList<>(warnings(resolution.from()));
        warnings.addAll(texts.warnings());
        return new EmailDtos.PreviewDto(
                resolution.from() == null ? null
                        : views.sender(resolution.from(), resolution.fromRole(), viewer, target.type()),
                views.recipients(resolution.recipients(), viewer, target.type()),
                resolution.unresolved(), resolution.problems(), warnings);
    }

    /**
     * An email written by hand about a record, its placeholders filled in as the caller sees it (A4, PC-10). Only a
     * customer, an invoice, a payment, a promise or a dispute offers them; text with no placeholder, or about any
     * other record, is kept exactly as written. A placeholder that is none, or not offered on this kind of record, is
     * a 400 on its field.
     */
    private RecordContexts.EmailTexts placeholders(EmailTargets.Target target, String subject, String body) {
        List<PlaceholderCatalogue.Item> offered = PlaceholderCatalogue.offered(target.type());
        boolean inSubject = PlaceholderRenderer.mayHavePlaceholders(subject);
        boolean inBody = PlaceholderRenderer.mayHavePlaceholders(body);
        if (offered.isEmpty() || !inSubject && !inBody) return new RecordContexts.EmailTexts(subject, body, List.of());
        requireOffered(target.type(), subject, body);
        return recordContexts.renderForEmail(target, subject, body);
    }

    private static void requireOffered(EmailEntityType type, String subject, String body) {
        List<PlaceholderCatalogue.Item> offered = PlaceholderCatalogue.offered(type);
        Map<String, String> errors = new LinkedHashMap<>();
        String where = type.noun() + " emails";
        PlaceholderRenderer.validate(subject, offered, where, "subject", errors);
        PlaceholderRenderer.validate(body, offered, where, "body", errors);
        if (!errors.isEmpty()) throw new GlobalExceptionHandler.InvalidFieldsException(errors);
    }

    /**
     * The email a rule would send about a record, its text already rendered, resolved exactly as sending it would be
     * and as whoever is signed in (the preview's caller, or the rule's owner): nothing is written, and what would stop
     * it is told in {@code problems} rather than thrown.
     */
    @Transactional(readOnly = true)
    public RenderedPreview previewRendered(EmailEntityType type, Long id, EmailToken from, boolean fallbackToOwner,
                                           List<EmailToken> to, String renderedSubject, String renderedBody,
                                           int maxRecipients) {
        AutomationResolution r = resolveForAutomation(type, id, from, fallbackToOwner, to, renderedSubject,
                renderedBody, maxRecipients);
        EmailViews.Viewer viewer = views.viewer();
        List<String> warnings = new ArrayList<>(r.warnings());
        EmailAddressing.Resolution resolution = r.resolution();
        if (resolution != null) warnings.addAll(warnings(resolution.from()));
        EmailDtos.PreviewDto email = resolution == null
                ? new EmailDtos.PreviewDto(null, List.of(), List.of(), r.problems(), warnings)
                : new EmailDtos.PreviewDto(
                        resolution.from() == null ? null
                                : views.sender(resolution.from(), resolution.fromRole(), viewer, type),
                        views.recipients(resolution.recipients(), viewer, type),
                        resolution.unresolved(), r.problems(), warnings);
        return new RenderedPreview(email, r.problems(), warnings);
    }

    /**
     * Steps 1 to 6 of sending an email for a rule (§5.10.5): the record as the signed-in person, the content, the
     * sender (a role nobody holds falls back to the owner when allowed), the recipients, and the automation visibility
     * rule: a person named who cannot see the record is not written to, nor made to write (PC-2, SC-3).
     */
    private AutomationResolution resolveForAutomation(EmailEntityType type, Long id, EmailToken from,
                                                      boolean fallbackToOwner, List<EmailToken> to, String subject,
                                                      String body, int maxRecipients) {
        return resolveForAutomation(targets.load(type, id), from, fallbackToOwner, to, subject, body, maxRecipients);
    }

    private AutomationResolution resolveForAutomation(EmailTargets.Target target, EmailToken from,
                                                      boolean fallbackToOwner, List<EmailToken> to, String subject,
                                                      String body, int maxRecipients) {
        EmailEntityType type = target.type();
        Long id = target.id();
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (target.regionId() != null && !currentUser.hasIn(Privileges.EMAIL_SEND, target.regionId())) {
            problems.add("Email cannot be sent about " + target.label() + ": EMAIL_SEND is not held in its region");
        }
        if (EmailText.oneLine(EmailText.storable(subject)).isEmpty()) problems.add("The subject is empty");
        if (body != null && body.length() > FieldLimits.EMAIL_BODY) {
            problems.add("The message is longer than " + FieldLimits.EMAIL_BODY + " characters");
        }

        List<EmailToken> recipients = new ArrayList<>();
        for (EmailToken token : to == null ? List.<EmailToken>of() : to) {
            if (token != null && "USER".equalsIgnoreCase(token.type()) && token.userId() != null
                    && !visibilityProbe.canSee(token.userId(), type, id, target.regionId())) {
                warnings.add(personName(token.userId()) + " cannot see this record, so was not emailed");
            } else {
                recipients.add(token);
            }
        }
        EmailToken sender = from;
        if (from != null && "USER".equalsIgnoreCase(from.type()) && from.userId() != null
                && !visibilityProbe.canSee(from.userId(), type, id, target.regionId())) {
            String who = personName(from.userId());
            sender = senderFallback(who + " cannot see this record, so cannot be the sender",
                    who + " cannot see this record, so it would come from the rule's owner", fallbackToOwner,
                    problems, warnings);
        } else if (from != null && "ROLE".equalsIgnoreCase(from.type())) {
            RoleRef role = EmailAddressing.requireOfferedRole(type, targets.rolesOffered(target), from);
            if (target.sender(role).isEmpty()) {
                String nobody = "Nobody holds " + role.label(type) + " on " + target.label();
                sender = senderFallback(nobody + ", so it cannot be the sender",
                        nobody + ", so it would come from the rule's owner", fallbackToOwner, problems, warnings);
            }
        }
        if (recipients.isEmpty()) {
            boolean onlyThat = problems.isEmpty();
            problems.add(to == null || to.isEmpty() ? "Add at least one recipient" : "No recipients left");
            return new AutomationResolution(target, null, problems, warnings, onlyThat);
        }

        EmailAddressing.Resolution resolution;
        try {
            resolution = addressing.resolve(addressing.plan(target, sender, recipients), target);
        } catch (GlobalExceptionHandler.InvalidFieldsException e) {
            problems.addAll(e.getFieldErrors().values());
            return new AutomationResolution(target, null, problems, warnings, false);
        } catch (BadRequestException | AccessDeniedException e) {
            problems.add(e.getMessage());
            return new AutomationResolution(target, null, problems, warnings, false);
        }
        if (resolution.fromRole() != null && resolution.from() != null && resolution.from().internal()
                && !visibilityProbe.canSee(resolution.from().userId(), type, id, target.regionId())) {
            String who = resolution.from().name();
            EmailToken fallback = senderFallback(who + " cannot see this record, so cannot be the sender",
                    who + " cannot see this record, so it would come from the rule's owner", fallbackToOwner,
                    problems, warnings);
            if (fallback == null && fallbackToOwner) {
                resolution = addressing.resolve(addressing.plan(target, null, recipients), target);
            }
        }
        List<RecipientSet.Entry> kept = new ArrayList<>();
        for (RecipientSet.Entry entry : resolution.recipients()) {
            if (entry.internal() && entry.userId() != null
                    && !visibilityProbe.canSee(entry.userId(), type, id, target.regionId())) {
                warnings.add(entry.name() + " cannot see this record, so was not emailed");
            } else {
                kept.add(entry);
            }
        }
        List<String> resolutionProblems = new ArrayList<>(resolution.problems());
        if (kept.isEmpty() && resolutionProblems.isEmpty()) resolutionProblems.add("No recipients left");
        boolean onlyNoRecipients = kept.isEmpty() && problems.isEmpty();
        problems.addAll(resolutionProblems);
        long toCount = kept.stream().filter(e -> e.field() == RecipientField.TO).count();
        if (toCount > maxRecipients) {
            problems.add("An email from a rule goes to at most " + maxRecipients + " people; this one would go to "
                    + toCount);
        }
        resolution = new EmailAddressing.Resolution(resolution.from(), resolution.fromRole(), kept,
                resolution.unresolved(), resolutionProblems);
        return new AutomationResolution(target, resolution, problems, warnings, onlyNoRecipients);
    }

    /**
     * Saves the email a rule sends about a record (§5.10.5): an ordinary OUTBOUND email on the record, sent by the
     * rule's owner (whoever is signed in), from an internal person (V5), to exactly the people left once those who
     * cannot see the record are dropped. Its text is already rendered. It is only queued, in the rule's action
     * transaction, never sent here: the caller sends it once that has committed (R15). Anything that stops it is a
     * {@link NotQueued}; content that cannot be saved is a 400 as for any email.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Queued queue(EmailEntityType type, Long id, EmailToken from, boolean fallbackToOwner, List<EmailToken> to,
                        String subject, String body, String batchId, int maxRecipients) {
        EmailTargets.Target target = targets.loadForSend(type, id);
        Content content = Content.check(subject, body, to);
        AutomationResolution r = resolveForAutomation(target, from, fallbackToOwner, to, content.subject(),
                content.body(), maxRecipients);
        if (!r.problems().isEmpty()) throw new NotQueued(String.join("; ", r.problems()), r.noRecipients());
        Long emailId = save(target, content, r.resolution(), EmailText.fit(batchId, Email.BATCH_MAX));
        List<String> warnings = new ArrayList<>(r.warnings());
        warnings.addAll(warnings(r.resolution().from()));
        return new Queued(emailId, warnings);
    }

    /** The sender cannot be used: the owner sends instead when the action allows it, otherwise it cannot go. */
    private static EmailToken senderFallback(String problem, String warning, boolean fallbackToOwner,
                                             List<String> problems, List<String> warnings) {
        if (fallbackToOwner) {
            warnings.add(warning);
        } else {
            problems.add(problem);
        }
        return null;
    }

    private String personName(Long userId) {
        return userRepository.findById(userId).map(EmailText::nameOf).orElse("User #" + userId);
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
        ViewScopeHolder.requireLive();
        EmailTargets.Target target = targets.loadForSend(EmailEntityType.parse(req.entityType()),
                requireId(req.entityId()));
        RecordContexts.EmailTexts texts = placeholders(target, req.subject(), req.body());
        Content content = Content.check(texts.subject(), texts.body(), req.to());
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
        ViewScopeHolder.requireLive();
        EmailEntityType type = EmailEntityType.parse(req.stringParam("entityType"));
        targets.requireTypeAccess(type);
        EmailToken from = param(req, "from", new TypeReference<EmailToken>() {});
        List<EmailToken> to = param(req, "to", new TypeReference<List<EmailToken>>() {});
        String subject = req.stringParam("subject");
        String body = req.stringParam("body");
        Content content = Content.check(subject, body, to);
        EmailAddressing.Plan plan = addressing.plan(type, from, to);
        // Each row gets its own record's values ({{Invoice.Number}}); a placeholder that is none refuses the send.
        boolean perRow = !PlaceholderCatalogue.offered(type).isEmpty()
                && (PlaceholderRenderer.mayHavePlaceholders(subject) || PlaceholderRenderer.mayHavePlaceholders(body));
        if (perRow) requireOffered(type, subject, body);

        List<Long> ids = targets.bulkIds(type, req);
        boolean truncated = req.allMatching() && ids.size() >= TableQueryExecutor.BULK_ID_LIMIT;
        String batchId = UUID.randomUUID().toString();
        Map<Long, Long> emailByRecord = new HashMap<>();
        BulkDtos.BulkResult result = bulkExecutor.run(req, ids, truncated, id -> {
            EmailTargets.Target target = targets.loadForSend(type, id);
            EmailAddressing.Resolution resolution;
            try {
                resolution = addressing.resolve(plan, target);
            } catch (GlobalExceptionHandler.InvalidFieldsException e) {
                // A person named for every row who cannot reach this row's region: this row does not qualify.
                throw new BulkExecutor.IneligibleException(
                        new java.util.TreeMap<>(e.getFieldErrors()).firstEntry().getValue());
            }
            resolution.problem().ifPresent(problem -> {
                throw new BulkExecutor.IneligibleException(problem);
            });
            emailByRecord.put(id, save(target, perRow ? rowContent(target, subject, body, to) : content,
                    resolution, batchId));
        });
        dispatcher.dispatchAll(result.succeeded().stream().map(emailByRecord::get).filter(Objects::nonNull).toList());
        return result;
    }

    /** One row's text of a bulk send, its placeholders filled in; a row whose text cannot be saved does not qualify. */
    private Content rowContent(EmailTargets.Target target, String subject, String body, List<EmailToken> to) {
        RecordContexts.EmailTexts texts = recordContexts.renderForEmail(target, subject, body);
        try {
            return Content.check(texts.subject(), texts.body(), to);
        } catch (GlobalExceptionHandler.InvalidFieldsException e) {
            Map.Entry<String, String> first = new java.util.TreeMap<>(e.getFieldErrors()).firstEntry();
            throw new BulkExecutor.IneligibleException(
                    ("subject".equals(first.getKey()) ? "Subject " : "Message ") + first.getValue());
        }
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
        ViewScopeHolder.requireLive();
        Email email = emailRepository.findById(id).orElseThrow(() -> new NotFoundException("Email not found"));
        targets.loadForSend(email.getEntityType(), email.getEntityId());
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

    /**
     * A record's emails, newest first; a customer sees only those their customer took part in (E13). As of a date,
     * those written or received by the end of that day (D8); how each was delivered is told as it is now.
     */
    public PageResponse<EmailDtos.EmailDto> list(String entityType, Long entityId, Integer page, Integer size) {
        EmailEntityType type = EmailEntityType.parse(entityType);
        targets.requireVisible(type, entityId);
        TableQuery query = TableQuery.parse(RECORD_EMAILS, page, size, null, List.of());
        EmailViews.Viewer viewer = views.viewer();
        List<PredicateFactory> scope = new ArrayList<>(List.of(
                (root, q, cb) -> cb.equal(root.get("entityType"), type),
                (root, q, cb) -> cb.equal(root.get("entityId"), entityId)));
        AsOfPoint point = AsOf.current();
        if (point != null) {
            scope.add((root, q, cb) -> cb.lessThanOrEqualTo(root.<Instant>get("occurredAt"), point.instant()));
        }
        if (viewer.isCustomer()) scope.add(EmailViews.customerTookPart(viewer.customerId()));
        var result = queryExecutor.run(Email.class, RECORD_EMAILS, query, scope, List.of());
        return PageResponse.of(views.toDtos(result.content(), viewer, true), query, result.total(), List.of());
    }

    /** One email; as of a date, one written or received after that day is missing (D8). */
    public EmailDtos.EmailDto get(Long id) {
        AsOfPoint point = AsOf.current();
        Email email = emailRepository.findById(id)
                .filter(e -> point == null || !e.getOccurredAt().isAfter(point.instant()))
                .orElseThrow(() -> new NotFoundException("Email not found"));
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

package com.geneinvoice.automation;

import com.geneinvoice.assignee.Assignee;
import com.geneinvoice.assignee.AssigneeOwnerType;
import com.geneinvoice.assignee.AssigneeService;
import com.geneinvoice.audit.AuditService;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.customer.CustomerRepository;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.dispute.DisputeRepository;
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.dispute.DisputeStatus;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.email.EmailDtos;
import com.geneinvoice.email.EmailEntityType;
import com.geneinvoice.email.EmailService;
import com.geneinvoice.email.EmailTargets;
import com.geneinvoice.poc.PocService;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.promise.PaymentPromise;
import com.geneinvoice.promise.PaymentPromiseRepository;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.promise.PromiseStatus;
import com.geneinvoice.task.Task;
import com.geneinvoice.task.TaskDtos;
import com.geneinvoice.task.TaskService;
import com.geneinvoice.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

/**
 * The THEN half of a rule: what actually happens once the WHERE has matched (R6).
 *
 * <p>This runs on a consumer thread with nobody logged in, which shapes everything here. The record
 * is read through {@link EmailTargets#loadInBackground}, the one loader that applies no privilege
 * and no customer restriction because there is no caller to apply them to (R7); it also hands back
 * the customer and the label without a lazy association in sight, which matters because
 * {@code open-in-view} is off and there is no EntityManager on this thread outside an explicit
 * transaction. Everything the actions attribute to a person is attributed to the rule's author,
 * who is the person answerable for it.
 *
 * <p>A promise and a dispute are written through their repositories rather than through
 * {@link PaymentPromiseService#create} and {@link DisputeService#open}, which both begin with
 * {@code currentUser.require()} and would throw a BadCredentialsException the moment they were
 * reached from here — and in the dispute's case would also refuse an internal caller outright,
 * since only a customer login may open one by hand. The rules those services enforce for a person
 * are enforced here for a rule: a promise still gets a real Collection POC from the customer's book
 * or the run skips, a dispute still belongs to the target's own customer, and both still get their
 * audit row.
 *
 * <p>Whatever cannot be done for this particular record is a {@link BadRequestException} or a
 * {@link NotFoundException}, which {@link AutomationWorker} records as a skip with the reason on
 * it. Nothing else is thrown deliberately; anything else means the app, not the rule, and is worth
 * retrying.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AutomationActions {

    /** A task or promise with no offset stated falls due a week out — a working week's notice. */
    static final int DEFAULT_DUE_IN_DAYS = 7;

    private final EmailTargets targets;
    private final AutomationJson json;
    private final AssigneeService assigneeService;
    private final PocService pocService;
    private final CustomerRepository customerRepository;
    private final PaymentPromiseRepository promiseRepository;
    private final DisputeRepository disputeRepository;
    private final TaskService taskService;
    private final EmailService emailService;
    private final AuditService auditService;
    private final CurrentUser currentUser;
    private final TransactionTemplate transactions;

    /**
     * Does the rule's THEN against one record, and says in a few words what it did, for the runs
     * list. No transaction is open around this: an action writes in its own short one, and the
     * email path must reach the mail service with none held at all.
     */
    public String perform(AutomationRule rule, AutomationEvent event) {
        EmailEntityType type = event.getEntityType().toEmailEntityType();
        EmailTargets.Target target = targets.loadInBackground(type, event.getEntityId())
                .orElseThrow(() -> new NotFoundException(
                        "This " + event.getEntityType().noun() + " no longer exists"));
        AutomationDtos.ActionSpec spec = json.action(rule);
        return switch (rule.getAction()) {
            case CREATE_TASK -> createTask(rule, event, target, spec);
            case CREATE_PROMISE -> createPromise(rule, event, target, spec);
            case CREATE_DISPUTE -> createDispute(rule, event, target, spec);
            case SEND_EMAIL -> sendEmail(rule, event, target, spec);
        };
    }

    // ---- the four actions ---------------------------------------------------------------

    /**
     * Raises a task on the record the rule fired against, with the assignees the rule picked. The
     * task service does its own assignee parsing, so the tokens go through as they were written and
     * one place stays answerable for what a task's assignees mean.
     */
    private String createTask(AutomationRule rule, AutomationEvent event, EmailTargets.Target target,
                              AutomationDtos.ActionSpec spec) {
        String title = require(spec.title(), "A task rule needs a title");
        Task task = taskService.createInBackground(new TaskDtos.CreateTaskRequest(
                event.getEntityType().name(), event.getEntityId(), title,
                dueDate(spec), spec.status(), spec.body(), tokens(spec)));
        log.info("Rule '{}' raised task {} on {} {}", rule.getName(), task.getId(),
                event.getEntityType().noun(), event.getEntityId());
        return "Raised task #" + task.getId() + " on " + target.label();
    }

    /**
     * Records a promise for the record's customer. The promised amount is the rule's, never read
     * off the record — see {@code ActionSpec} — and the Collection POC is the customer's own, since
     * a promise nobody is answerable for is not a promise anyone will chase (AC-B5).
     */
    private String createPromise(AutomationRule rule, AutomationEvent event, EmailTargets.Target target,
                                 AutomationDtos.ActionSpec spec) {
        Long customerId = requireCustomer(target, event);
        BigDecimal amount = spec.amount();
        if (amount == null || amount.signum() <= 0) {
            throw new BadRequestException("A promise rule needs an amount greater than zero");
        }
        User poc = pocService.defaultAssignee(customerId, PocType.COLLECTION)
                .orElseThrow(() -> new BadRequestException(
                        "This customer has no active Collection POC, so there is nobody to answer for the promise"));
        List<Assignee> assignees = assigneeService.parse(EmailEntityType.PROMISE, customerId, tokens(spec));

        Long id = transactions.execute(status -> {
            Customer customer = customerRepository.findById(customerId)
                    .orElseThrow(() -> new NotFoundException("Customer not found"));
            PaymentPromise promise = promiseRepository.save(PaymentPromise.builder()
                    .customer(customer)
                    .amount(amount)
                    .promisedDate(dueDate(spec))
                    .collectionPoc(poc)
                    .notes(fit(spec.body(), FieldLimits.PROMISE_NOTES))
                    .status(PromiseStatus.OPEN)
                    .fulfilledAmount(BigDecimal.ZERO)
                    .createdByUserId(actor(rule))
                    .build());
            assigneeService.replace(AssigneeOwnerType.PROMISE, promise.getId(), assignees);
            auditService.record(PaymentPromiseService.ENTITY, promise.getId(), "PROMISE_CREATED",
                    null, made(rule, event, "promised " + amount + " by " + promise.getPromisedDate()),
                    actor(rule), null, by(rule));
            return promise.getId();
        });
        log.info("Rule '{}' recorded promise {} for customer {}", rule.getName(), id, customerId);
        return "Recorded promise #" + id + " for " + target.label();
    }

    /**
     * Raises a dispute against the invoice or payment the rule fired against. A customer rule
     * cannot reach here — a dispute is about one invoice or one payment, and that is refused when
     * the rule is written rather than skipped once per record forever.
     */
    private String createDispute(AutomationRule rule, AutomationEvent event, EmailTargets.Target target,
                                 AutomationDtos.ActionSpec spec) {
        DisputeTargetType targetType = disputeTarget(event.getEntityType());
        Long customerId = requireCustomer(target, event);
        String reason = fit(require(spec.body(), "A dispute rule needs a reason"), FieldLimits.DISPUTE_TEXT);
        Long openedBy = actor(rule);
        if (openedBy == null) {
            throw new BadRequestException("This rule has no author, so there is nobody to raise the dispute as");
        }
        // One open dispute per target is the rule a person gets; a rule that fired on an edit must
        // not stack a second one on top of the one somebody is already working through.
        if (disputeRepository.existsByCustomerIdAndTargetTypeAndTargetIdAndStatus(
                customerId, targetType, event.getEntityId(), DisputeStatus.PENDING)) {
            throw new BadRequestException("An open dispute already exists for this "
                    + event.getEntityType().noun());
        }
        List<Assignee> assignees = assigneeService.parse(EmailEntityType.DISPUTE, customerId, tokens(spec));

        Long id = transactions.execute(status -> {
            Dispute dispute = disputeRepository.save(Dispute.builder()
                    .customerId(customerId)
                    .openedByUserId(openedBy)
                    .targetType(targetType)
                    .targetId(event.getEntityId())
                    .reason(reason)
                    .status(DisputeStatus.PENDING)
                    .build());
            assigneeService.replace(AssigneeOwnerType.DISPUTE, dispute.getId(), assignees);
            auditService.record(DisputeService.ENTITY, dispute.getId(), "DISPUTE_OPENED",
                    null, made(rule, event, "against " + targetType.name().toLowerCase(Locale.ROOT)
                            + " #" + event.getEntityId()),
                    openedBy, dispute.getId(), by(rule));
            return dispute.getId();
        });
        log.info("Rule '{}' opened dispute {} on {} {}", rule.getName(), id,
                event.getEntityType().noun(), event.getEntityId());
        return "Opened dispute #" + id + " on " + target.label();
    }

    /**
     * Sends one email about the record, through the same service the compose form uses, so the
     * recipients resolve against this record's own role holders at the moment it goes out (E4) and
     * the email lands in the record's Email tab like any other.
     */
    private String sendEmail(AutomationRule rule, AutomationEvent event, EmailTargets.Target target,
                             AutomationDtos.ActionSpec spec) {
        String subject = require(spec.title(), "An email rule needs a subject");
        List<EmailDtos.EmailToken> to = tokens(spec);
        if (to.isEmpty()) {
            throw new BadRequestException("An email rule needs at least one recipient");
        }
        Long id = emailService.sendInBackground(event.getEntityType().toEmailEntityType(),
                event.getEntityId(), spec.from(), to, subject, spec.body(), List.of());
        log.info("Rule '{}' sent email {} about {} {}", rule.getName(), id,
                event.getEntityType().noun(), event.getEntityId());
        return "Sent email #" + id + " about " + target.label();
    }

    // ---- small shared pieces -------------------------------------------------------------

    /**
     * Who the rule acts as. On a consumer thread there is nobody logged in, so it is the rule's
     * author — the person who chose that this would happen. {@code idOrNull} rather than
     * {@code require} because this code runs with no SecurityContext and {@code require} throws
     * there; where there IS a caller (an inline run in a test, say) they are the truer answer.
     */
    private Long actor(AutomationRule rule) {
        Long caller = currentUser.idOrNull();
        return caller != null ? caller : rule.getCreatedByUserId();
    }

    /** What the audit trail says about why this happened, so a record's history names the rule. */
    private static String by(AutomationRule rule) {
        return "Automation rule: " + rule.getName();
    }

    /**
     * What the audit row keeps of something a rule made. The record itself is one read away; what
     * the trail needs is which rule made it, what set that rule off, and the few facts somebody
     * checking would want without opening anything.
     *
     * <p>Only the promise and the dispute get one of these. A task and an email are written by
     * their own services, which record their own history and would only be repeating themselves.
     */
    public record RuleMade(Long ruleId, String ruleName, TriggerKind trigger,
                           AutomationEntityType entityType, Long entityId, String summary) {}

    private static RuleMade made(AutomationRule rule, AutomationEvent event, String summary) {
        return new RuleMade(rule.getId(), rule.getName(), event.getTrigger(),
                event.getEntityType(), event.getEntityId(), summary);
    }

    private static List<EmailDtos.EmailToken> tokens(AutomationDtos.ActionSpec spec) {
        return spec.assignees() == null ? List.of() : spec.assignees();
    }

    /** The day the task falls due or the promise is for, counted from the run rather than from the record. */
    private static LocalDate dueDate(AutomationDtos.ActionSpec spec) {
        int days = spec.dueInDays() == null ? DEFAULT_DUE_IN_DAYS : spec.dueInDays();
        return LocalDate.now(ZoneOffset.UTC).plusDays(days);
    }

    /**
     * A promise and a dispute both belong to a customer. A record whose customer has gone — or a
     * kind that never had one — is a skip with the reason, not a row with a null customer.
     */
    private static Long requireCustomer(EmailTargets.Target target, AutomationEvent event) {
        if (target.customerId() == null) {
            throw new BadRequestException("This " + event.getEntityType().noun()
                    + " belongs to no customer, so there is nothing to record against");
        }
        return target.customerId();
    }

    static DisputeTargetType disputeTarget(AutomationEntityType type) {
        return switch (type) {
            case INVOICE -> DisputeTargetType.INVOICE;
            case PAYMENT -> DisputeTargetType.PAYMENT;
            case CUSTOMER -> throw new BadRequestException(
                    "A dispute is raised against an invoice or a payment, not a customer");
        };
    }

    /**
     * Cuts text to the column that will hold it. The lengths are checked when the rule is written,
     * so this only catches a rule written before that check, or one whose action was changed to a
     * kind with a tighter column: a run that quietly shortens a note is better than one that dies
     * on an insert the author cannot see.
     */
    private static String fit(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max - 1) + "…";
    }

    private static String require(String value, String message) {
        if (value == null || value.isBlank()) throw new BadRequestException(message);
        return value;
    }
}

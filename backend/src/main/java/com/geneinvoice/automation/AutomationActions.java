package com.geneinvoice.automation;

import com.geneinvoice.approval.ApprovalContext;
import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.email.AutomationEmailWriter;
import com.geneinvoice.email.EmailDtos;
import com.geneinvoice.email.EmailEntityType;
import com.geneinvoice.email.EmailTargets;
import com.geneinvoice.email.Placeholders;
import com.geneinvoice.email.RenderContext;
import com.geneinvoice.email.RoleRef;
import com.geneinvoice.email.RoleResolver;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.invoice.InvoiceStatus;
import com.geneinvoice.privilege.Privileges;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.region.RegionScope;
import com.geneinvoice.task.Task;
import com.geneinvoice.task.TaskAssignee;
import com.geneinvoice.task.TaskEntityType;
import com.geneinvoice.task.TaskService;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The four things a rule can do, each performed by THE SAME service method a person uses (A3).
 *
 * <p>That is the whole design of this class and it is worth saying plainly: there is no second way
 * to create a task, a promise, a dispute or an email in this application. B2's approval gate and
 * B1's region guard live inside those service bodies, so the automated path walks into exactly the
 * same two checks the human path does, rather than into copies of them that drift (A3, B1, B2).
 *
 * <p>EVERY action runs inside TWO wrappers and neither is decoration:
 * <ul>
 *   <li>the NARROWING region hatch, bounded by the rule's OWN regions. Never the widening one: a
 *       consumer thread has no principal, so without a hatch every read is refused and with the
 *       widening hatch every read is permitted. An EMPTY set denies rather than widens, which is
 *       what makes an author who lost every grant reach nothing (A3, B1).</li>
 *   <li>{@code approvalContext.actingAs(rule.createdByUserId)}, so a change the gate holds names a
 *       real, accountable person as its maker. A pending change with a NULL maker would make
 *       "approval from someone else" trivially satisfiable — anybody could approve it (A3, B2).</li>
 * </ul>
 *
 * <p>AUTHORITY IS CHECKED HERE AND NOWHERE ELSE. The three explicit-actor service methods validate
 * only that an actor was named; a deactivated or demoted author must not write with dead
 * authority, so the privilege is asked for by name before every action and a missing one settles
 * the step SKIPPED with a sentence a person can read. Visible, never silent (A3).
 */
@Component
@RequiredArgsConstructor
public class AutomationActions {

    /** What a person would be told, said once here so both halves of a skip read the same (A3). */
    static final String NO_LONGER_HAS = " no longer has ";
    static final String CANNOT_RUN = ", so this rule cannot run";

    private final TaskService taskService;
    private final PaymentPromiseService promiseService;
    private final DisputeService disputeService;
    private final AutomationEmailWriter emailWriter;
    private final Placeholders placeholders;
    private final RoleResolver roleResolver;
    private final InvoiceRepository invoiceRepository;
    private final UserRepository userRepository;
    // The engine's answer to "who is accountable" while the SecurityContext is empty (B2, A5).
    private final ApprovalContext approvalContext;

    /**
     * What one action did, in exactly the facts {@code AutomationStepRepository.settle} needs.
     *
     * <p>A SKIP IS A SUCCESSFUL OUTCOME of running the step, not a failure of it: "this correctly
     * did not happen" is a sentence the run history has to be able to say, and a queue that
     * reports it as an error asks somebody to fix something that is already right (A5).
     */
    public record Outcome(ProducedType producedType, Long producedId, List<String> unresolved,
                          String skipReason, Long emailId) {

        static Outcome made(ProducedType type, Long id, List<String> unresolved) {
            return new Outcome(type, id, distinct(unresolved), null, null);
        }

        static Outcome drafted(Long emailId, List<String> unresolved) {
            return new Outcome(ProducedType.EMAIL, emailId, distinct(unresolved), null, emailId);
        }

        static Outcome skip(String reason) {
            return new Outcome(null, null, List.of(), reason, null);
        }

        public boolean skipped() {
            return skipReason != null;
        }

        private static List<String> distinct(List<String> raw) {
            return raw == null ? List.of() : List.copyOf(new LinkedHashSet<>(raw));
        }
    }

    /**
     * Everything one action needs, gathered ONCE per step by the dispatcher.
     *
     * <p>{@code render} carries the record, its account, the role snapshot and the run's as-of date
     * — and a cache keyed by placeholder alone, so it is good for THIS record and no other (A4).
     */
    public record Act(AutomationRule rule, AutomationStep step, ActionSpec spec,
                      Set<Long> reach, RenderContext render) {

        EmailEntityType type() {
            return step.getSubjectType().emailType();
        }

        Customer customer() {
            return render.customer();
        }

        LocalDate asOf() {
            return render.asOf();
        }
    }

    /**
     * MANDATORY: the domain row this writes must commit with the step's settle or not at all. A
     * worker whose fence has been taken must leave NOTHING behind, and the only way to make that
     * true is for the write and the settle to be one transaction (A5).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome perform(Act act) {
        Long actor = act.rule().getCreatedByUserId();
        // The narrowing hatch, then the actor. Both are ThreadLocals with save-and-restore
        // semantics, so a nested action cannot leak either one out of this call (A3, B1, B2).
        return RegionScope.asRegions(act.reach(), RegionScope.SystemReason.AUTOMATION_ACT, () ->
                approvalContext.actingAs(actor, () -> run(act, actor)));
    }

    private Outcome run(Act act, Long actor) {
        // Not a pattern switch: Java 17 without --enable-preview refuses one over a sealed
        // interface, which A-RULES hit in the same place (A3).
        ActionSpec spec = act.spec();
        if (spec instanceof ActionSpec.CreateTask task) return createTask(act, actor, task);
        if (spec instanceof ActionSpec.CreatePromise promise) return createPromise(act, actor, promise);
        if (spec instanceof ActionSpec.CreateDispute dispute) return createDispute(act, actor, dispute);
        if (spec instanceof ActionSpec.SendEmail email) return sendEmail(act, actor, email);
        // Unreachable while the interface stays sealed, and written down anyway (A3).
        throw new IllegalStateException("Unknown action " + spec.getClass().getSimpleName());
    }

    // ---- the four actions -------------------------------------------------------------------

    private Outcome createTask(Act act, Long actor, ActionSpec.CreateTask spec) {
        Outcome denied = authority(actor, Privileges.TASK_MANAGE);
        if (denied != null) return denied;

        List<String> unresolved = new ArrayList<>();
        String title = oneLine(render(spec.title(), act, unresolved));
        if (title == null || title.isBlank()) return Outcome.skip("The task title rendered empty");
        String notes = render(spec.notes(), act, unresolved);
        // Days from the RUN'S as-of date and never from the wall clock, so a replayed run produces
        // the same due date it produced the first time (A5, B3).
        LocalDate due = spec.dueInDays() == null ? null : act.asOf().plusDays(spec.dueInDays());

        List<Long> assignees = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        resolveAssignees(spec.assignees(), act, unresolved, assignees, sources);

        Task task = taskService.createFromRule(actor, act.rule().getId(), act.step().getId(),
                TaskEntityType.valueOf(act.step().getSubjectType().name()), act.step().getSubjectId(),
                title, notes, due, assignees, sources);
        return Outcome.made(ProducedType.TASK, task.getId(), unresolved);
    }

    private Outcome createPromise(Act act, Long actor, ActionSpec.CreatePromise spec) {
        Outcome denied = authority(actor, Privileges.PROMISE_MANAGE);
        if (denied != null) return denied;

        BigDecimal amount = amountFor(act, spec);
        if (amount == null || amount.signum() <= 0) {
            // Nothing owed is not a failure and must never be retried: the record simply stopped
            // being worth chasing between the save and the act (A3, A5).
            return Outcome.skip("There is nothing outstanding to promise");
        }
        List<String> unresolved = new ArrayList<>();
        LocalDate promised = act.asOf().plusDays(spec.promisedInDays() == null ? 0 : spec.promisedInDays());
        Long poc = resolveOnePerson(spec.collectionPoc(), act, unresolved);
        String notes = fit(render(spec.notes(), act, unresolved), FieldLimits.PROMISE_NOTES);

        PromiseDtos.PromiseDto promise = promiseService.createAs(actor,
                new PromiseDtos.CreatePromiseRequest(act.customer().getId(), amount, promised,
                        poc, notes, promisedInvoices(act)));
        return Outcome.made(ProducedType.PROMISE, promise.id(), unresolved);
    }

    private Outcome createDispute(Act act, Long actor, ActionSpec.CreateDispute spec) {
        Outcome denied = authority(actor, Privileges.DISPUTE_CREATE);
        if (denied != null) return denied;
        if (act.step().getSubjectType() == SubjectType.CUSTOMER) {
            // Refused at save time too; defended here because a rule saved before that check
            // existed would otherwise reach DisputeTargetType.valueOf with no constant (A3).
            return Outcome.skip("There is nothing to dispute on a customer");
        }

        List<String> unresolved = new ArrayList<>();
        String reason = fit(render(spec.reason(), act, unresolved), FieldLimits.DISPUTE_TEXT);
        Dispute dispute = disputeService.openAs(actor, act.customer().getId(),
                DisputeTargetType.valueOf(act.step().getSubjectType().name()),
                act.step().getSubjectId(), reason);
        return Outcome.made(ProducedType.DISPUTE, dispute.getId(), unresolved);
    }

    private Outcome sendEmail(Act act, Long actor, ActionSpec.SendEmail spec) {
        Outcome denied = authority(actor, Privileges.EMAIL_SEND);
        if (denied != null) return denied;

        List<String> unresolved = new ArrayList<>();
        String subject = oneLine(render(spec.subject(), act, unresolved));
        if (subject == null || subject.isBlank()) {
            // Exactly what EmailService.Content.check would refuse. Refused here rather than
            // stored and then rejected by a 400 nobody is there to read (A4, A5).
            return Outcome.skip("The subject rendered empty");
        }
        String body = render(spec.body(), act, unresolved);

        AutomationEmailWriter.Drafted drafted = emailWriter.draft(act.render().target(), spec.from(),
                spec.to(), subject, body, actor);
        // Nobody resolved at all — no sender, or no recipient of any kind. The writer wrote
        // nothing and hands back the sentence to settle with (A3, A5).
        if (drafted.problem() != null) return Outcome.skip(drafted.problem());
        unresolved.addAll(drafted.unresolved());
        return Outcome.drafted(drafted.emailId(), unresolved);
    }

    // ---- the pieces -------------------------------------------------------------------------

    /**
     * Is the rule's author still allowed to do this, off-request?
     *
     * <p>{@code hasPrivilege} and {@code isActive} are asked EVERY time rather than frozen onto the
     * rule, for the same reason the regions are resolved live: taking somebody's authority away has
     * to mean their rules stop using it, today, without anybody remembering to re-save every rule
     * they ever wrote (A3).
     *
     * @return null when the author may proceed, and the skip to settle with when they may not
     */
    private Outcome authority(Long actorUserId, String privilege) {
        User author = actorUserId == null ? null : userRepository.findById(actorUserId).orElse(null);
        if (author == null) {
            return Outcome.skip("The rule's author no longer exists" + CANNOT_RUN);
        }
        if (!author.isActive() || !userRepository.hasPrivilege(actorUserId, privilege)) {
            return Outcome.skip(author.getUsername() + NO_LONGER_HAS + privilege + CANNOT_RUN);
        }
        return null;
    }

    private String render(String template, Act act, List<String> unresolved) {
        if (template == null) return null;
        Placeholders.Rendered rendered = placeholders.render(template, act.render());
        unresolved.addAll(rendered.unresolved());
        return rendered.text();
    }

    /**
     * Seats, not people (A3, A6).
     *
     * <p>A role token resolves to the PRIMARY holder, which is {@code Target.sender} — the first of
     * a list the POC book returns primary-first, filtered to active people. There is deliberately
     * no second definition of "primary" in this codebase and this is not the place to invent one
     * (A4). A seat nobody holds is RECORDED as unresolved and the task is still made: a task with
     * one of its two owners missing is better than no task at all, and the run history says which.
     */
    private void resolveAssignees(List<EmailDtos.EmailToken> tokens, Act act, List<String> unresolved,
                                  List<Long> assignees, List<String> sources) {
        if (tokens == null) return;
        for (EmailDtos.EmailToken token : tokens) {
            if (token == null) continue;
            if (isType(token, "USER")) {
                add(assignees, sources, token.userId(), TaskAssignee.SOURCE_USER);
                continue;
            }
            if (!isType(token, "ROLE")) {
                // A CUSTOMER token is refused when the rule is saved; a customer contact can be
                // told about a task and can never own one (A3, A6).
                unresolved.add("A customer cannot own a task");
                continue;
            }
            RoleRef ref = roleResolver.toRoleRef(act.type(), token);
            Optional<EmailTargets.Person> primary = roleResolver.primary(act.render().target(), ref)
                    .filter(EmailTargets.Person::internal);
            if (primary.isEmpty()) {
                unresolved.add(ref.label(act.type()));
                continue;
            }
            // The stored source is RoleRef.token(), so the task detail can say WHY somebody is on
            // it rather than only that they are (A6).
            add(assignees, sources, primary.get().userId(), ref.token());
        }
    }

    /**
     * READ THE TOKEN TYPE THE WAY THE SAVE PATH READ IT (A3, A6).
     *
     * <p>{@code AutomationRuleService.kind} accepts a type by {@code trim()} +
     * {@code equalsIgnoreCase}, and so does {@code EmailAddressing.kind} on the SEND_EMAIL arm at
     * act time — the house convention every enum in this codebase follows. These four comparisons
     * were the only ones left that read it case-sensitively, so a rule posted with
     * {@code "type":"role"} saved cleanly, passed the offered-role check, and then resolved to
     * NOBODY at run time: a promise quietly owned by the account's default collection POC instead
     * of the person the rule named, and a task carrying the false sentence "A customer cannot own
     * a task" for a token that is a ROLE. Validate and execute have to read the same string the
     * same way.
     */
    private static boolean isType(EmailDtos.EmailToken token, String wanted) {
        return token != null && token.type() != null && wanted.equalsIgnoreCase(token.type().trim());
    }

    /** One person behind a token, or null with the seat recorded as unresolved (A3). */
    private Long resolveOnePerson(EmailDtos.EmailToken token, Act act, List<String> unresolved) {
        if (token == null) return null;
        if (isType(token, "USER")) return token.userId();
        if (!isType(token, "ROLE")) return null;
        RoleRef ref = roleResolver.toRoleRef(act.type(), token);
        Optional<EmailTargets.Person> primary = roleResolver.primary(act.render().target(), ref)
                .filter(EmailTargets.Person::internal);
        if (primary.isEmpty()) {
            unresolved.add(ref.label(act.type()));
            return null;
        }
        return primary.get().userId();
    }

    private static void add(List<Long> assignees, List<String> sources, Long userId, String source) {
        // De-duplicated HERE because `sources` is POSITIONAL against `assignees`: TaskService
        // de-duplicates the ids on its own and would then read the wrong source for the survivor
        // (A6).
        if (userId == null || assignees.contains(userId)) return;
        assignees.add(userId);
        sources.add(source);
    }

    /**
     * Where a promised amount comes from.
     *
     * <p>CUSTOMER_OUTSTANDING is the same sum the customers list publishes as its own
     * {@code outstanding} column — one correlated aggregate, asked of the repository that owns it,
     * rather than a third spelling of "what this account owes" (A3).
     */
    private BigDecimal amountFor(Act act, ActionSpec.CreatePromise spec) {
        if (spec.amountFrom() == null) return spec.fixedAmount();
        return switch (spec.amountFrom()) {
            case FIXED -> spec.fixedAmount();
            case INVOICE_BALANCE -> act.render().entity() instanceof Invoice invoice
                    ? invoice.getBalance() : null;
            case CUSTOMER_OUTSTANDING -> outstandingOf(act.customer().getId());
        };
    }

    private BigDecimal outstandingOf(Long customerId) {
        return invoiceRepository.sumOutstandingByCustomer(List.of(customerId)).stream()
                .findFirst().map(row -> (BigDecimal) row[1]).orElse(BigDecimal.ZERO);
    }

    /**
     * A promise about an invoice is linked to that invoice; a promise about an account is not.
     *
     * <p>A cancelled invoice is left unlinked rather than passed in, because
     * {@code resolveInvoices} refuses one out loud and a rule must not fail on a record it was
     * only ever asked to chase (A3).
     */
    private List<Long> promisedInvoices(Act act) {
        if (act.step().getSubjectType() != SubjectType.INVOICE) return List.of();
        return act.render().entity() instanceof Invoice invoice
                && invoice.getStatus() != InvoiceStatus.CANCELLED
                ? List.of(invoice.getId()) : List.of();
    }

    /**
     * A title and a subject are a single trimmed line (E16).
     *
     * <p>The twin of {@code EmailText.oneLine}, which is package-private inside
     * {@code com.geneinvoice.email} and deliberately stayed that way: A-EMAIL-SEAMS widened
     * nothing, so the four characters of its body live here rather than the whole class being
     * opened up for them (A4).
     */
    static String oneLine(String text) {
        return text == null ? null : text.replaceAll("\\r\\n|\\r|\\n", " ").trim();
    }

    /** The EmailText.fit habit, per destination column, AFTER rendering (A4). */
    static String fit(String text, int max) {
        if (text == null || text.length() <= max) return text;
        return text.substring(0, max - 1) + "…";
    }
}

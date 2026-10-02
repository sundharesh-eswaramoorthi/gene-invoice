package com.geneinvoice.automation.action;

import com.geneinvoice.assignee.AssigneeService;
import com.geneinvoice.assignee.AssigneeStore;
import com.geneinvoice.assignee.AssignedTo;
import com.geneinvoice.approval.Outcome;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.automation.placeholder.PlaceholderRenderer;
import com.geneinvoice.automation.placeholder.Rendered;
import com.geneinvoice.automation.rule.TriggerEntity;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.common.GlobalExceptionHandler;
import com.geneinvoice.common.Money;
import com.geneinvoice.email.EmailDtos.EmailToken;
import com.geneinvoice.email.EmailEntityType;
import com.geneinvoice.email.EmailRole;
import com.geneinvoice.email.EmailTargets;
import com.geneinvoice.email.EmailText;
import com.geneinvoice.email.RoleLevel;
import com.geneinvoice.email.RoleRef;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDates;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.poc.PocType;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.promise.PromiseDtos;
import com.geneinvoice.promise.PromiseStatus;
import com.geneinvoice.region.RegionAccessQueries;
import com.geneinvoice.user.User;
import com.geneinvoice.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CREATE_PROMISE (§5.10.5): a payment promise for the record's customer, of a fixed sum or of one of the record's
 * figures, worked by the customer's Collection POC unless the action names one. It is made as the rule's owner
 * through the same gate as a person's: above the region's limit it is held for someone else to approve (D11).
 */
@Component
@RequiredArgsConstructor
public class CreatePromiseHandler implements ActionHandler<CreatePromiseAction> {

    public static final int OFFSET_MAX = 365;
    public static final String RESULT = "PROMISE";
    public static final String RESULT_HELD = "PENDING_CHANGE";
    static final String POC_LEFT_OUT = "The Collection POC is left out: POC details are not visible here";
    static final String NOTHING_OWED = "Nothing owed";
    private static final int MONEY_DIGITS = 12;

    private final AssigneeStore assigneeStore;
    private final AssigneeService assigneeService;
    private final UserRepository userRepository;
    private final InvoiceRepository invoiceRepository;
    private final CurrentUser currentUser;
    private final RegionAccessQueries regionAccess;
    private final PaymentPromiseService promiseService;

    @PersistenceContext
    private EntityManager em;

    @Override
    public ActionType type() {
        return ActionType.CREATE_PROMISE;
    }

    @Override
    public Class<CreatePromiseAction> actionClass() {
        return CreatePromiseAction.class;
    }

    @Override
    public CreatePromiseAction normalize(CreatePromiseAction a, TriggerEntity entity, String path,
                                         Map<String, String> errors) {
        amount(a.amount(), entity, path + ".amount", errors);
        ActionSupport.due(a.promisedDate(), entity, 0, OFFSET_MAX, path + ".promisedDate", errors);
        ActionSupport.text(a.notes(), false, FieldLimits.PROMISE_NOTES, entity, path + ".notes", errors);
        if (a.linkRecord() && entity != TriggerEntity.INVOICE) {
            errors.put(path + ".linkRecord", "Only invoice rules can link the promise to the record");
        }
        List<RoleRef> pocRoles = entity == TriggerEntity.PAYMENT
                ? List.of(RoleRef.customer(EmailRole.COLLECTION_POC), RoleRef.record(EmailRole.COLLECTION_POC))
                : List.of(RoleRef.customer(EmailRole.COLLECTION_POC));
        EmailToken poc = ActionSupport.person(userRepository, entity.emailType(), pocRoles, a.collectionPoc(),
                path + ".collectionPoc", errors);
        List<EmailToken> tokens = ActionSupport.assignees(assigneeStore, EmailEntityType.PROMISE, a.assignees(),
                EmailTargets.offeredRoles(EmailEntityType.PROMISE), path + ".assignees", errors);
        return a.withPeople(poc, tokens);
    }

    private static void amount(AmountSpec amount, TriggerEntity entity, String path, Map<String, String> errors) {
        if (amount == null || amount.source() == null) {
            errors.put(path + ".source", "source must be FIXED or FIELD");
            return;
        }
        if (amount.source() == AmountSpec.Source.FIXED) {
            BigDecimal v = amount.value();
            if (v == null || v.signum() <= 0) {
                errors.put(path + ".value", "must be more than 0");
            } else if (v.stripTrailingZeros().scale() > 2) {
                errors.put(path + ".value", Money.CENTS_MESSAGE);
            } else if (v.precision() - v.scale() > MONEY_DIGITS) {
                errors.put(path + ".value", "is too large");
            }
            return;
        }
        String field = amount.field();
        if (field == null || !AmountSpec.FIELDS.contains(field)) {
            errors.put(path + ".field", "field must be one of " + AmountSpec.FIELDS);
        } else if (field.startsWith("Invoice.") && entity != TriggerEntity.INVOICE
                || field.startsWith("Payment.") && entity != TriggerEntity.PAYMENT) {
            errors.put(path + ".field", field + " is not available on " + entity.noun() + " rules");
        }
    }

    /**
     * Works out the promise and whom it would be told to, all read-only, before any lock (§5.10.6). Nothing owed is
     * no failure: there is simply no promise to ask for, nor anyone to work it, and perform skips it.
     */
    @Override
    public Prepared prepare(CreatePromiseAction a, RecordContext rc) {
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (!currentUser.hasIn(type().privilege(), rc.regionId())) problems.add(ActionSupport.notAllowed(type()));
        Map<String, Object> values = new LinkedHashMap<>();
        BigDecimal amount = Money.scale(amountOf(a.amount(), rc));
        values.put("amount", amount);
        boolean owed = amount.signum() > 0;
        DueSpec date = a.promisedDate() == null ? new DueSpec(DueSpec.Base.TODAY, 0) : a.promisedDate();
        values.put("promisedDate", ActionSupport.day(date, rc, warnings).toString());
        Optional<EmailTargets.Person> poc = owed ? collectionPoc(a.collectionPoc(), rc) : Optional.empty();
        if (owed && poc.isEmpty()) {
            problems.add("No Collection POC");
        } else if (poc.isPresent()) {
            if (a.collectionPoc() != null && "USER".equals(a.collectionPoc().type())) {
                notAssignable(poc.get(), rc.regionId()).ifPresent(problems::add);
            }
            values.put("collectionPocUserId", poc.get().userId());
            values.put("collectionPoc", poc.get().name());
        }
        if (a.notes() != null && !a.notes().isBlank()) {
            Rendered notes = PlaceholderRenderer.render(a.notes(), rc.values(), FieldLimits.PROMISE_NOTES, false,
                    "Notes");
            ActionSupport.collect(notes, warnings);
            values.put("notes", notes.text());
        }
        values.put("linkRecord", a.linkRecord() && rc.type() == TriggerEntity.INVOICE);
        AssigneeService.Probed probed = assigneeService.probe(AssignedTo.PROMISE,
                a.assignees() == null ? List.of() : a.assignees(), rc.type().emailType(), rc.id(), rc.regionId());
        warnings.addAll(probed.warnings());
        List<EmailToken> kept;
        try {
            kept = assigneeStore.validate(EmailEntityType.PROMISE, probed.kept(),
                    EmailTargets.offeredRoles(EmailEntityType.PROMISE));
        } catch (GlobalExceptionHandler.InvalidFieldsException e) {
            problems.add(e.getFieldErrors().values().iterator().next());
            kept = List.of();
        }
        AssigneeService.NewRecordNotice notice = owed && problems.isEmpty()
                ? assigneeService.recipientsForNew(AssignedTo.PROMISE, kept, rc.target(), rc.regionId(),
                        poc.get().userId())
                : null;
        return new Prepared(values, kept, problems, warnings, notice);
    }

    /** What the promise would be; who works it is left out for someone who may not see POCs (PC-7). */
    @Override
    public ActionPreview preview(CreatePromiseAction a, RecordContext rc) {
        Prepared p = prepare(a, rc);
        Map<String, Object> values = p.values();
        List<String> warnings = new ArrayList<>(p.warnings());
        List<String> problems = new ArrayList<>(p.problems());
        if (((BigDecimal) values.get("amount")).signum() <= 0) {
            warnings.add(0, NOTHING_OWED + ", so none would be made");
        }
        if (a.onlyIfNoOpenPromise() && openPromise(rc)) warnings.add(guardMessage(rc) + ", so none would be made");
        if (!rc.values().canSeePoc() && values.containsKey("collectionPoc")) {
            values = new LinkedHashMap<>(values);
            values.remove("collectionPocUserId");
            values.remove("collectionPoc");
            warnings.add(POC_LEFT_OUT);
        }
        return new ActionPreview(a.key(), type().name(), values,
                assigneeService.describe(AssignedTo.PROMISE, p.assignees(), rc.type().emailType(), rc.target()),
                null, problems, warnings);
    }

    /**
     * Asks for the promise as the rule's owner, under the customer's lock taken by the caller: nothing owed, or an
     * open promise already there when the action says so (asked again here), leaves it undone. A promise made is
     * {@code Done(PROMISE)} and its co-assignees are told, as {@link #prepare} worked out; one held above the
     * region's limit is {@code Done(PENDING_CHANGE)}, so the effect's key stops a redelivery asking twice (D11), and
     * its co-assignees are assigned and told when it is approved.
     */
    @Override
    public ActionResult perform(CreatePromiseAction a, RecordContext rc, Prepared p) {
        if (!p.problems().isEmpty()) throw new BadRequestException(String.join("; ", p.problems()));
        BigDecimal amount = (BigDecimal) p.values().get("amount");
        if (amount.signum() <= 0) return new ActionResult.Skipped(NOTHING_OWED);
        if (a.onlyIfNoOpenPromise() && openPromise(rc)) return new ActionResult.Skipped(guardMessage(rc));
        boolean link = Boolean.TRUE.equals(p.values().get("linkRecord"));
        Outcome<PromiseDtos.PromiseDto> out = promiseService.createOrHold(new PromiseDtos.CreatePromiseRequest(
                rc.customerId(), amount, LocalDate.parse((String) p.values().get("promisedDate")),
                (Long) p.values().get("collectionPocUserId"), (String) p.values().get("notes"),
                link ? List.of(rc.id()) : List.of(), p.assignees()));
        if (out instanceof Outcome.Held<PromiseDtos.PromiseDto> held) {
            return new ActionResult.Done(RESULT_HELD, held.change().getId(), p.warnings());
        }
        PromiseDtos.PromiseDto made = out.appliedOrThrow();
        assigneeService.notifyNew(AssignedTo.PROMISE, made.id(), rc.customerId(), made.regionId(), p.assignees(),
                (AssigneeService.NewRecordNotice) p.plan());
        return new ActionResult.Done(RESULT, made.id(), p.warnings());
    }

    private static String guardMessage(RecordContext rc) {
        return rc.type() == TriggerEntity.INVOICE ? "An open promise is already linked to this invoice"
                : "The customer already has an open promise";
    }

    private BigDecimal amountOf(AmountSpec spec, RecordContext rc) {
        if (spec.source() == AmountSpec.Source.FIXED) return spec.value();
        return switch (spec.field()) {
            case "Customer.Outstanding" -> first(invoiceRepository.sumOutstandingByCustomer(List.of(rc.customerId())));
            case "Customer.OverdueAmount" -> first(invoiceRepository.sumOverdueByCustomer(
                    List.of(rc.customerId()), InvoiceDates.today()));
            case "Invoice.Balance" -> ((Invoice) rc.entity()).getBalance();
            case "Invoice.Total" -> ((Invoice) rc.entity()).getTotal();
            case "Payment.Amount" -> ((Payment) rc.entity()).getAmount();
            default -> BigDecimal.ZERO;
        };
    }

    /** Who works the promise: the person named, the role's holder, or else the customer's own Collection POC. */
    private Optional<EmailTargets.Person> collectionPoc(EmailToken token, RecordContext rc) {
        if (token == null) return rc.target().sender(RoleRef.customer(EmailRole.COLLECTION_POC));
        if ("USER".equals(token.type())) {
            return userRepository.findById(token.userId()).filter(User::isActive).map(u -> new EmailTargets.Person(
                    u.getId(), EmailText.nameOf(u), u.getEmail(), null, true));
        }
        RoleRef role = new RoleRef(EmailRole.COLLECTION_POC,
                RoleLevel.parse(token.level() == null ? RoleLevel.CUSTOMER.name() : token.level()));
        return rc.target().sender(role);
    }

    /**
     * Why the person named cannot work the promise in the record's region, as {@code PocService.requireAssignable}
     * would refuse them (§5.10.5); asked without throwing, so a problem found here never spoils the transaction.
     */
    private Optional<String> notAssignable(EmailTargets.Person person, Long regionId) {
        String privilege = PocType.COLLECTION.assignabilityPrivilege();
        User u = userRepository.findById(person.userId()).orElse(null);
        if (u == null || u.getCustomerId() != null || !userRepository.hasPrivilege(u.getId(), privilege)) {
            return Optional.of(person.name() + " is not assignable as " + PocType.COLLECTION.label());
        }
        if (!regionAccess.holds(u.getId(), privilege, regionId)) {
            return Optional.of(person.name() + " is not assignable as " + PocType.COLLECTION.label()
                    + " in the record's region");
        }
        return Optional.empty();
    }

    private boolean openPromise(RecordContext rc) {
        String jpql = rc.type() == TriggerEntity.INVOICE
                ? "select count(p) from PaymentPromise p join p.invoices i where i.id = :id and p.status = :open"
                : "select count(p) from PaymentPromise p where p.customer.id = :id and p.status = :open";
        Long open = em.createQuery(jpql, Long.class)
                .setParameter("id", rc.type() == TriggerEntity.INVOICE ? rc.id() : rc.customerId())
                .setParameter("open", PromiseStatus.OPEN)
                .getSingleResult();
        return open > 0;
    }

    private static BigDecimal first(List<Object[]> rows) {
        return rows.isEmpty() || rows.get(0)[1] == null ? BigDecimal.ZERO : (BigDecimal) rows.get(0)[1];
    }
}

package com.geneinvoice.automation.action;

import com.geneinvoice.assignee.AssigneeService;
import com.geneinvoice.assignee.AssigneeStore;
import com.geneinvoice.assignee.AssignedTo;
import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.automation.placeholder.PlaceholderRenderer;
import com.geneinvoice.automation.placeholder.Rendered;
import com.geneinvoice.automation.rule.TriggerEntity;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.common.GlobalExceptionHandler;
import com.geneinvoice.dispute.Dispute;
import com.geneinvoice.dispute.DisputeRepository;
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.dispute.DisputeStatus;
import com.geneinvoice.dispute.DisputeTargetType;
import com.geneinvoice.dispute.InternalDisputeRequest;
import com.geneinvoice.email.EmailDtos.EmailToken;
import com.geneinvoice.email.EmailEntityType;
import com.geneinvoice.email.RoleRef;
import com.geneinvoice.notification.NotificationService;
import com.geneinvoice.region.Region;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CREATE_DISPUTE (§5.10.5): an internal dispute on the record for someone to review, proposing nothing, a cancel
 * (an invoice) or a void (a payment). It is opened as the rule's owner, who can then neither make the change it
 * proposes nor approve it once held (§5.10.11); its assignees are told, or else the region's administrators.
 */
@Component
@RequiredArgsConstructor
public class CreateDisputeHandler implements ActionHandler<CreateDisputeAction> {

    public static final String RESULT = "DISPUTE";
    static final String ALREADY_OPEN = "An internal dispute is already open on this record";
    static final String NOTIF_OPENED = "DISPUTE_OPENED";

    private final AssigneeStore assigneeStore;
    private final AssigneeService assigneeService;
    private final DisputeService disputeService;
    private final DisputeRepository disputeRepository;
    private final NotificationService notificationService;
    private final CurrentUser currentUser;

    @Override
    public ActionType type() {
        return ActionType.CREATE_DISPUTE;
    }

    @Override
    public Class<CreateDisputeAction> actionClass() {
        return CreateDisputeAction.class;
    }

    /** Customer-level roles everywhere, plus the record's own POC on an invoice or a payment (§5.9.1). */
    public static List<RoleRef> allowedRoles(TriggerEntity entity) {
        return DisputeService.assigneeRoles(targetOf(entity));
    }

    private static DisputeTargetType targetOf(TriggerEntity entity) {
        return switch (entity) {
            case INVOICE -> DisputeTargetType.INVOICE;
            case PAYMENT -> DisputeTargetType.PAYMENT;
            case CUSTOMER -> DisputeTargetType.CUSTOMER;
        };
    }

    @Override
    public CreateDisputeAction normalize(CreateDisputeAction a, TriggerEntity entity, String path,
                                         Map<String, String> errors) {
        ActionSupport.text(a.reason(), true, FieldLimits.DISPUTE_TEXT, entity, path + ".reason", errors);
        String proposal = a.proposedChange() == null || a.proposedChange().isBlank()
                ? CreateDisputeAction.NONE : a.proposedChange().trim().toLowerCase();
        boolean allowed = proposal.equals(CreateDisputeAction.NONE)
                || proposal.equals(CreateDisputeAction.CANCEL) && entity == TriggerEntity.INVOICE
                || proposal.equals(CreateDisputeAction.VOID) && entity == TriggerEntity.PAYMENT;
        if (!allowed) {
            errors.put(path + ".proposedChange", switch (entity) {
                case INVOICE -> "An invoice dispute proposes none or cancel";
                case PAYMENT -> "A payment dispute proposes none or void";
                case CUSTOMER -> "A customer dispute proposes none";
            });
        }
        List<EmailToken> tokens = ActionSupport.assignees(assigneeStore, EmailEntityType.DISPUTE, a.assignees(),
                allowedRoles(entity), path + ".assignees", errors);
        return a.withAssignees(proposal, tokens);
    }

    /**
     * Renders the reason, drops the people named who cannot see the record (with a warning each), then works out whom
     * the dispute would be told to: all read-only, before any lock (§5.10.6).
     */
    @Override
    public Prepared prepare(CreateDisputeAction a, RecordContext rc) {
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (!currentUser.hasIn(type().privilege(), rc.regionId())) problems.add(ActionSupport.notAllowed(type()));
        Map<String, Object> values = new LinkedHashMap<>();
        Rendered reason = PlaceholderRenderer.render(a.reason(), rc.values(), FieldLimits.DISPUTE_TEXT, false,
                "Reason");
        ActionSupport.collect(reason, warnings);
        values.put("reason", reason.text() == null || reason.text().isBlank() ? rc.label() : reason.text());
        values.put("proposedChange", a.proposedChange() == null ? CreateDisputeAction.NONE : a.proposedChange());
        AssigneeService.Probed probed = assigneeService.probe(AssignedTo.DISPUTE,
                a.assignees() == null ? List.of() : a.assignees(), rc.type().emailType(), rc.id(), rc.regionId());
        warnings.addAll(probed.warnings());
        List<EmailToken> kept;
        try {
            kept = assigneeStore.validate(EmailEntityType.DISPUTE, probed.kept(), allowedRoles(rc.type()));
        } catch (GlobalExceptionHandler.InvalidFieldsException e) {
            problems.add(e.getFieldErrors().values().iterator().next());
            kept = List.of();
        }
        AssigneeService.NewRecordNotice notice = problems.isEmpty()
                ? assigneeService.recipientsForNew(AssignedTo.DISPUTE, kept, rc.target(), rc.regionId(), null) : null;
        return new Prepared(values, kept, problems, warnings, notice);
    }

    @Override
    public ActionPreview preview(CreateDisputeAction a, RecordContext rc) {
        Prepared p = prepare(a, rc);
        List<String> warnings = new ArrayList<>(p.warnings());
        if (openInternal(rc)) warnings.add(ALREADY_OPEN + ", so none would be raised");
        return new ActionPreview(a.key(), type().name(), p.values(),
                assigneeService.describe(AssignedTo.DISPUTE, p.assignees(), rc.type().emailType(), rc.target()),
                null, p.problems(), warnings);
    }

    /**
     * Opens the dispute as the rule's owner, under the customer's lock taken by the caller: one already waiting on
     * the record (from any rule) is asked about again here and leaves this one undone. Its assignees are told, as
     * {@link #prepare} worked out; when nobody is, the region's administrators hear of it, as they do of a customer's
     * dispute.
     */
    @Override
    public ActionResult perform(CreateDisputeAction a, RecordContext rc, Prepared p) {
        if (!p.problems().isEmpty()) throw new BadRequestException(String.join("; ", p.problems()));
        if (openInternal(rc)) return new ActionResult.Skipped(ALREADY_OPEN);
        Dispute d = disputeService.openInternal(new InternalDisputeRequest(targetOf(rc.type()), rc.id(),
                (String) p.values().get("reason"), (String) p.values().get("proposedChange"), rc.ruleId(),
                p.assignees()));
        int told = assigneeService.notifyNew(AssignedTo.DISPUTE, d.getId(), d.getCustomerId(),
                Region.idOf(d.getRegion()), p.assignees(), (AssigneeService.NewRecordNotice) p.plan());
        if (told == 0) {
            notificationService.notifyAdmins(NOTIF_OPENED, "Dispute raised on " + rc.label(), d.getReason(),
                    "/disputes/" + d.getId(), Region.idOf(d.getRegion()));
        }
        return new ActionResult.Done(RESULT, d.getId(), p.warnings());
    }

    private boolean openInternal(RecordContext rc) {
        return disputeRepository.existsByCustomerIdAndTargetTypeAndTargetIdAndStatusAndInternalTrue(rc.customerId(),
                targetOf(rc.type()), rc.id(), DisputeStatus.PENDING);
    }
}

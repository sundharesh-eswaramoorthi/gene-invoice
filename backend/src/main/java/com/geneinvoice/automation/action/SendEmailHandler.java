package com.geneinvoice.automation.action;

import com.geneinvoice.auth.CurrentUser;
import com.geneinvoice.automation.AutomationProperties;
import com.geneinvoice.automation.placeholder.PlaceholderRenderer;
import com.geneinvoice.automation.placeholder.Rendered;
import com.geneinvoice.automation.rule.TriggerEntity;
import com.geneinvoice.common.FieldLimits;
import com.geneinvoice.common.origin.WriteOrigins;
import com.geneinvoice.email.EmailDtos.EmailToken;
import com.geneinvoice.email.EmailService;
import com.geneinvoice.email.EmailTargets;
import com.geneinvoice.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SEND_EMAIL (§5.10.5): an email about the record, from its owner, a person or a role, to people, roles and the
 * customer. The preview resolves it exactly as sending would, through {@link EmailService#previewRendered}; acting
 * queues it through {@link EmailService#queue} as an ordinary outbound email on the record, which the consumer sends
 * once the action has committed (R15). It needs no customer lock: it changes nothing of the customer's.
 */
@Component
@RequiredArgsConstructor
public class SendEmailHandler implements ActionHandler<SendEmailAction> {

    public static final int MAX_TO = 10;
    static final String RESULT = "EMAIL";

    private final UserRepository userRepository;
    private final EmailService emailService;
    private final AutomationProperties props;
    private final CurrentUser currentUser;

    @Override
    public ActionType type() {
        return ActionType.SEND_EMAIL;
    }

    @Override
    public Class<SendEmailAction> actionClass() {
        return SendEmailAction.class;
    }

    @Override
    public SendEmailAction normalize(SendEmailAction a, TriggerEntity entity, String path,
                                     Map<String, String> errors) {
        EmailToken from = ActionSupport.person(userRepository, entity.emailType(),
                EmailTargets.offeredRoles(entity.emailType()), a.from(), path + ".from", errors);
        List<EmailToken> to = new ArrayList<>();
        if (a.to() == null || a.to().isEmpty() || a.to().size() > MAX_TO) {
            errors.put(path + ".to", "An email goes to 1 to " + MAX_TO + " recipients");
        } else {
            for (int i = 0; i < a.to().size(); i++) {
                EmailToken token = a.to().get(i);
                if (token != null && "CUSTOMER".equalsIgnoreCase(token.type())) {
                    to.add(EmailToken.customer());
                } else {
                    to.add(ActionSupport.person(userRepository, entity.emailType(),
                            EmailTargets.offeredRoles(entity.emailType()), token, path + ".to", errors));
                }
            }
        }
        ActionSupport.text(a.subject(), true, FieldLimits.EMAIL_SUBJECT, entity, path + ".subject", errors);
        ActionSupport.text(a.body(), false, FieldLimits.EMAIL_BODY, entity, path + ".body", errors);
        return a.withPeople(from, to);
    }

    @Override
    public Prepared prepare(SendEmailAction a, RecordContext rc) {
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (!currentUser.hasIn(type().privilege(), rc.regionId())) problems.add(ActionSupport.notAllowed(type()));
        Map<String, Object> values = new LinkedHashMap<>();
        Rendered subject = PlaceholderRenderer.render(a.subject(), rc.values(), FieldLimits.EMAIL_SUBJECT, true,
                "Subject");
        ActionSupport.collect(subject, warnings);
        Rendered body = PlaceholderRenderer.render(a.body() == null ? "" : a.body(), rc.values(),
                FieldLimits.EMAIL_BODY, false, "Message");
        ActionSupport.collect(body, warnings);
        values.put("subject", subject.text());
        values.put("body", body.text());
        return new Prepared(values, List.of(), problems, warnings);
    }

    /**
     * Queues the email with the texts {@link #prepare} rendered, as the rule's owner, in the action's transaction;
     * anyone who cannot see the record is left out (PC-2, SC-3). Why it could not be queued is thrown: nobody left to
     * write to skips the action, anything else fails it.
     */
    @Override
    public ActionResult perform(SendEmailAction a, RecordContext rc, Prepared p) {
        EmailService.Queued queued = emailService.queue(rc.type().emailType(), rc.id(), a.from(), a.fallbackToOwner(),
                a.to(), (String) p.values().get("subject"), (String) p.values().get("body"),
                "rule-" + rc.ruleId() + "-run-" + WriteOrigins.current(currentUser).runId(),
                props.getMaxEmailRecipients());
        List<String> warnings = new ArrayList<>(p.warnings());
        warnings.addAll(queued.warnings());
        return new ActionResult.Done(RESULT, queued.emailId(), warnings);
    }

    @Override
    public boolean locksCustomer() {
        return false;
    }

    @Override
    public ActionPreview preview(SendEmailAction a, RecordContext rc) {
        Prepared p = prepare(a, rc);
        EmailService.RenderedPreview email = emailService.previewRendered(rc.type().emailType(), rc.id(), a.from(),
                a.fallbackToOwner(), a.to(), (String) p.values().get("subject"), (String) p.values().get("body"),
                props.getMaxEmailRecipients());
        return new ActionPreview(a.key(), type().name(), p.values(), null, email.email(), p.problems(),
                p.warnings());
    }
}

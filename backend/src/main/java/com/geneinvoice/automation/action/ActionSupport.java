package com.geneinvoice.automation.action;

import com.geneinvoice.assignee.AssigneeStore;
import com.geneinvoice.automation.placeholder.PlaceholderRenderer;
import com.geneinvoice.automation.placeholder.Rendered;
import com.geneinvoice.automation.rule.TriggerEntity;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.GlobalExceptionHandler;
import com.geneinvoice.email.EmailAddressing;
import com.geneinvoice.email.EmailDtos.EmailToken;
import com.geneinvoice.email.EmailEntityType;
import com.geneinvoice.email.RoleRef;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.user.UserRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** What the action handlers check and work out alike. */
final class ActionSupport {

    private ActionSupport() {}

    /** Text a person wrote: required or not, at most {@code max} characters, its placeholders offered here. */
    static void text(String text, boolean required, int max, TriggerEntity entity, String path,
                     Map<String, String> errors) {
        if (text == null || text.isBlank()) {
            if (required) errors.put(path, "must not be blank");
            return;
        }
        if (text.length() > max) {
            errors.put(path, "must be at most " + max + " characters");
            return;
        }
        PlaceholderRenderer.validate(text, entity, path, errors);
    }

    /** A date's rule: {@code INVOICE_DUE_DATE} only on invoice rules, and an offset within the range. */
    static void due(DueSpec due, TriggerEntity entity, int minOffset, int maxOffset, String path,
                    Map<String, String> errors) {
        if (due == null) return;
        if (due.base() == null) {
            errors.put(path + ".base", "base must be TODAY or INVOICE_DUE_DATE");
        } else if (due.base() == DueSpec.Base.INVOICE_DUE_DATE && entity != TriggerEntity.INVOICE) {
            errors.put(path + ".base", "Only invoice rules can count from the invoice's due date");
        }
        if (due.offset() < minOffset || due.offset() > maxOffset) {
            errors.put(path + ".offsetDays", "must be between " + minOffset + " and " + maxOffset);
        }
    }

    /** People and roles to assign, as {@link AssigneeStore} stores them; a problem goes under {@code path}. */
    static List<EmailToken> assignees(AssigneeStore store, EmailEntityType context, List<EmailToken> tokens,
                                      List<RoleRef> allowed, String path, Map<String, String> errors) {
        try {
            return store.validate(context, tokens, allowed);
        } catch (GlobalExceptionHandler.InvalidFieldsException e) {
            errors.put(path, e.getFieldErrors().values().iterator().next());
            return tokens == null ? List.of() : tokens;
        }
    }

    /** One person or role named on its own (a sender, a Collection POC): as a recipient would be checked. */
    static EmailToken person(UserRepository users, EmailEntityType type, List<RoleRef> offered, EmailToken token,
                             String path, Map<String, String> errors) {
        if (token == null) return null;
        String kind = token.type() == null ? "" : token.type().trim().toUpperCase();
        try {
            return switch (kind) {
                case "USER" -> EmailToken.user(EmailAddressing.requireActiveInternalUser(users, token.userId()).getId());
                case "ROLE" -> EmailToken.role(EmailAddressing.requireOfferedRole(type, offered, token));
                default -> throw new BadRequestException("must be a person or a role");
            };
        } catch (BadRequestException e) {
            errors.put(path, e.getMessage());
            return token;
        }
    }

    /**
     * The day a date spec comes to for this record: today in the rule's zone, or the invoice's due date, plus the
     * offset, never before today.
     */
    static LocalDate day(DueSpec due, RecordContext rc, List<String> warnings) {
        LocalDate base = rc.today();
        if (due.base() == DueSpec.Base.INVOICE_DUE_DATE && rc.entity() instanceof Invoice invoice) {
            if (invoice.getDueDate() == null) {
                warnings.add("The invoice has no due date, so today was counted from");
            } else {
                base = invoice.getDueDate();
            }
        }
        LocalDate day = base.plusDays(due.offset());
        return day.isBefore(rc.today()) ? rc.today() : day;
    }

    /** The rendered text's warnings, the hidden customer's said once however many texts ask for it. */
    static void collect(Rendered rendered, List<String> warnings) {
        for (String w : rendered.warnings()) {
            if (w.equals(PlaceholderRenderer.CUSTOMER_HIDDEN) && warnings.contains(w)) continue;
            warnings.add(w);
        }
    }

    /** Why the rule could not act here: whoever it runs as lacks the action's privilege in the record's region. */
    static String notAllowed(ActionType type) {
        return switch (type) {
            case CREATE_TASK -> "Tasks cannot be created in this record's region: TASK_MANAGE is not held there";
            case CREATE_PROMISE -> "Promises cannot be made in this record's region: PROMISE_MANAGE is not held there";
            case CREATE_DISPUTE -> "Disputes cannot be opened in this record's region: DISPUTE_MANAGE is not held there";
            case SEND_EMAIL -> "Email cannot be sent about this record: EMAIL_SEND is not held in its region";
        };
    }
}

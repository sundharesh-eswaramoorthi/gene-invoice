package com.geneinvoice.automation.run;

import com.geneinvoice.approval.ChangeConflictException;
import com.geneinvoice.approval.PayloadException;
import com.geneinvoice.auth.RunAs;
import com.geneinvoice.automation.action.ActionSkippedException;
import com.geneinvoice.automation.eval.RuleDefinitionException;
import com.geneinvoice.automation.placeholder.PlaceholderRenderer;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.DatabaseErrors;
import com.geneinvoice.common.GlobalExceptionHandler;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.bulk.BulkExecutor;
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.email.EmailService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * What a failed action means for the run (§5.10.6), asked in this order: the dedupe key already acted (DUPLICATE);
 * nothing to act on (SKIP); try again later (TRANSIENT: concurrency is ruled out before the database counts as gone,
 * S8); the database is gone (INFRA: stop the drain); the rule itself is at fault (DEFINITION); the answer will not
 * change (PERMANENT). Anything else is taken as transient.
 */
@Component
@Slf4j
public class FailureClassifier {

    public enum Kind { DUPLICATE, SKIP, TRANSIENT, INFRA, DEFINITION, PERMANENT }

    static final String DEDUPE_CONSTRAINT = "uk_automation_effect_dedupe";

    public Kind classify(Throwable e) {
        if (e instanceof DataIntegrityViolationException && mentions(e, DEDUPE_CONSTRAINT)) return Kind.DUPLICATE;
        if (e instanceof ActionSkippedException || e instanceof NotFoundException
                || e instanceof EmailService.NotQueued n && n.noRecipients()
                || e instanceof DisputeService.AlreadyOpenException) {
            return Kind.SKIP;
        }
        if (DatabaseErrors.isConcurrency(e) || e instanceof QueryTimeoutException) return Kind.TRANSIENT;
        if (DatabaseErrors.isUnavailable(e)) return Kind.INFRA;
        if (e instanceof RuleDefinitionException || e instanceof PlaceholderRenderer.UnknownPlaceholderException) {
            return Kind.DEFINITION;
        }
        if (permanent(e)) {
            if (e instanceof AuthenticationException) {
                // No principal where the owner's should be: a bug, never a business answer (C-8).
                log.error("automation.action.unauthenticated: {}", e.getMessage(), e);
            }
            return Kind.PERMANENT;
        }
        return Kind.TRANSIENT;
    }

    /**
     * The business answers. The first ones listed do not extend {@link BadRequestException}, so they are named one by
     * one (CF-9); an action a later release carries out is refused the same way every time.
     */
    private static boolean permanent(Throwable e) {
        return e instanceof BadRequestException
                || e instanceof GlobalExceptionHandler.InvalidFieldsException
                || e instanceof BulkExecutor.IneligibleException
                || e instanceof ChangeConflictException
                || e instanceof PayloadException
                || e instanceof EmailService.NotQueued
                || e instanceof AccessDeniedException
                || e instanceof AuthenticationException
                || e instanceof DataIntegrityViolationException
                || e instanceof IllegalArgumentException
                || e instanceof UnsupportedOperationException
                || e instanceof RunAs.InactiveUserException;
    }

    /** Whether the constraint's name appears anywhere in the cause chain, whatever its case. */
    private static boolean mentions(Throwable e, String constraint) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains(constraint)) return true;
            if (t.getCause() == t) break;
        }
        return false;
    }
}

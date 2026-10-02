package com.geneinvoice.automation.run;

import com.geneinvoice.approval.ApprovalConflictException;
import com.geneinvoice.approval.ChangeConflictException;
import com.geneinvoice.approval.PayloadException;
import com.geneinvoice.auth.RunAs;
import com.geneinvoice.automation.action.ActionSkippedException;
import com.geneinvoice.automation.eval.RuleDefinitionException;
import com.geneinvoice.automation.placeholder.PlaceholderRenderer;
import com.geneinvoice.automation.run.FailureClassifier.Kind;
import com.geneinvoice.common.BadRequestException;
import com.geneinvoice.common.GlobalExceptionHandler;
import com.geneinvoice.common.NotFoundException;
import com.geneinvoice.common.bulk.BulkExecutor;
import com.geneinvoice.common.scope.CodedBadRequestException;
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.email.EmailService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLTransientConnectionException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a failed action means (§5.10.6), one case per row of the table, in its order: a duplicate of an effect
 * already made, nothing to act on, try again, the database is gone (only once concurrency is ruled out, S8), the
 * rule is at fault, or the answer will not change, the exceptions that do not extend BadRequestException named one by
 * one (CF-9). Plain: no Spring context.
 */
class FailureClassifierTest {

    private final FailureClassifier classifier = new FailureClassifier();

    private Kind of(Throwable e) {
        return classifier.classify(e);
    }

    @Test
    void theEffectsOwnUniqueKeyIsADuplicateAndAnyOtherIntegrityFailureIsPermanent() {
        assertThat(of(new DataIntegrityViolationException("could not execute statement",
                new RuntimeException("Unique index or primary key violation: \"PUBLIC.UK_AUTOMATION_EFFECT_DEDUPE"
                        + "_INDEX_4 ON PUBLIC.AUTOMATION_EFFECTS(DEDUPE_KEY)\"")))).isEqualTo(Kind.DUPLICATE);
        assertThat(of(new DataIntegrityViolationException("duplicate key value violates unique constraint "
                + "\"uk_automation_effect_dedupe\""))).isEqualTo(Kind.DUPLICATE);
        assertThat(of(new DataIntegrityViolationException("NULL not allowed for column \"TITLE\"")))
                .isEqualTo(Kind.PERMANENT);
    }

    @Test
    void nothingToActOnIsASkip() {
        assertThat(of(new ActionSkippedException("The record no longer exists"))).isEqualTo(Kind.SKIP);
        assertThat(of(new NotFoundException("Invoice not found"))).isEqualTo(Kind.SKIP);
        assertThat(of(new EmailService.NotQueued("No recipients: the customer has no email address", true)))
                .as("an email with nobody to go to").isEqualTo(Kind.SKIP);
        assertThat(of(new DisputeService.AlreadyOpenException("An internal dispute is already open on this invoice")))
                .as("a dispute already waiting, though a bad request").isEqualTo(Kind.SKIP);
    }

    @Test
    void concurrencyIsTransientAndIsAskedBeforeTheDatabaseCountsAsGone() {
        assertThat(of(new OptimisticLockingFailureException("changed"))).isEqualTo(Kind.TRANSIENT);
        assertThat(of(new ObjectOptimisticLockingFailureException("Invoice", 7L))).isEqualTo(Kind.TRANSIENT);
        assertThat(of(new PessimisticLockingFailureException("lock"))).isEqualTo(Kind.TRANSIENT);
        // A lock timeout is also a transient data access failure: concurrency wins (S8).
        assertThat(of(new CannotAcquireLockException("Timeout trying to lock table \"CUSTOMERS\"")))
                .isEqualTo(Kind.TRANSIENT);
        assertThat(of(new IllegalStateException("wrapped", new CannotAcquireLockException("timeout"))))
                .isEqualTo(Kind.TRANSIENT);
        assertThat(of(new QueryTimeoutException("slow"))).isEqualTo(Kind.TRANSIENT);
    }

    @Test
    void aDatabaseThatWentAwayIsInfrastructure() {
        assertThat(of(new DataAccessResourceFailureException("connection refused"))).isEqualTo(Kind.INFRA);
        assertThat(of(new CannotCreateTransactionException("no connection"))).isEqualTo(Kind.INFRA);
        assertThat(of(new RuntimeException("wrapped", new SQLTransientConnectionException("pool exhausted"))))
                .isEqualTo(Kind.INFRA);
    }

    @Test
    void theRuleAtFaultIsADefinitionProblemThoughBothAreBadRequests() {
        assertThat(of(new RuleDefinitionException("The rule's condition cannot be used"))).isEqualTo(Kind.DEFINITION);
        assertThat(of(new PlaceholderRenderer.UnknownPlaceholderException("Unknown placeholder {{X}}")))
                .isEqualTo(Kind.DEFINITION);
    }

    @Test
    void businessAnswersArePermanentIncludingThoseThatAreNoBadRequest() {
        assertThat(of(new BadRequestException("Nothing owed"))).isEqualTo(Kind.PERMANENT);
        assertThat(of(new CodedBadRequestException("AS_OF_READ_ONLY", "read only"))).isEqualTo(Kind.PERMANENT);
        assertThat(of(new ApprovalConflictException("Another change is waiting"))).isEqualTo(Kind.PERMANENT);
        assertThat(of(new GlobalExceptionHandler.InvalidFieldsException(Map.of("assignees", "At most 10"))))
                .isEqualTo(Kind.PERMANENT);
        assertThat(of(new BulkExecutor.IneligibleException("Already cancelled"))).isEqualTo(Kind.PERMANENT);
        assertThat(of(new ChangeConflictException("Due date was changed by someone else"))).isEqualTo(Kind.PERMANENT);
        assertThat(of(new PayloadException("unreadable"))).isEqualTo(Kind.PERMANENT);
        assertThat(of(new EmailService.NotQueued("Nobody holds Collection POC, so it cannot be the sender", false)))
                .isEqualTo(Kind.PERMANENT);
        assertThat(of(new AccessDeniedException("Not allowed"))).isEqualTo(Kind.PERMANENT);
        assertThat(of(new AuthenticationCredentialsNotFoundException("no principal"))).isEqualTo(Kind.PERMANENT);
        assertThat(of(new IllegalArgumentException("bad"))).isEqualTo(Kind.PERMANENT);
        assertThat(of(new UnsupportedOperationException("CREATE_PROMISE actions cannot be carried out yet")))
                .isEqualTo(Kind.PERMANENT);
        assertThat(of(new RunAs.InactiveUserException("User #7 cannot act now"))).isEqualTo(Kind.PERMANENT);
    }

    @Test
    void anythingElseIsTakenAsTransient() {
        assertThat(of(new IllegalStateException("surprise"))).isEqualTo(Kind.TRANSIENT);
        assertThat(of(new RuntimeException())).isEqualTo(Kind.TRANSIENT);
    }
}

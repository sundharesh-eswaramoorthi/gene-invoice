package com.geneinvoice.automation;

import com.geneinvoice.approval.ApprovalContext;
import com.geneinvoice.dispute.DisputeService;
import com.geneinvoice.email.AutomationEmailWriter;
import com.geneinvoice.email.Placeholders;
import com.geneinvoice.email.RoleResolver;
import com.geneinvoice.invoice.InvoiceRepository;
import com.geneinvoice.promise.PaymentPromiseService;
import com.geneinvoice.task.TaskService;
import com.geneinvoice.user.UserRepository;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The two things a test cannot otherwise stand in the middle of: a failure inside tx2, and
 * ANOTHER WORKER taking the step while this one is still in it.
 *
 * <p>The RecordingMailTransport shape — a fake bean in a nested test configuration — because the
 * alternatives are worse. A transient failure in production is a lock timeout or a lost
 * connection, and neither is reachable from a test without breaking the datasource; the retry,
 * backoff and poison machinery is nevertheless the thing most worth pinning, so the failure is
 * injected at the one seam it would arrive through. The interleave hook is not a simulation at
 * all: it runs whatever the test gives it, and the fence tests give it a real reclaim in a real
 * committed transaction, which is exactly what a sweeper on another instance does (A5).
 *
 * <p>{@code @Primary} because AutomationDispatcher asks for AutomationActions by type. Everything
 * not being interfered with is delegated to the real object, so a test that sets neither hook is
 * testing the real engine.
 */
@TestConfiguration
public class InterleavingActions {

    @Bean
    @Primary
    AutomationActions interleavingActions(TaskService taskService,
                                          PaymentPromiseService promiseService,
                                          DisputeService disputeService,
                                          AutomationEmailWriter emailWriter,
                                          Placeholders placeholders,
                                          RoleResolver roleResolver,
                                          InvoiceRepository invoiceRepository,
                                          UserRepository userRepository,
                                          ApprovalContext approvalContext) {
        return new Interleaving(taskService, promiseService, disputeService, emailWriter,
                placeholders, roleResolver, invoiceRepository, userRepository, approvalContext);
    }

    public static class Interleaving extends AutomationActions {

        /** Thrown on EVERY attempt until a test clears it, which is what the poison test needs. */
        public static volatile RuntimeException fail;

        /** Run ONCE, inside tx2, just before the action itself. Cleared as it fires. */
        public static volatile Runnable interleave;

        Interleaving(TaskService taskService, PaymentPromiseService promiseService,
                     DisputeService disputeService, AutomationEmailWriter emailWriter,
                     Placeholders placeholders, RoleResolver roleResolver,
                     InvoiceRepository invoiceRepository, UserRepository userRepository,
                     ApprovalContext approvalContext) {
            super(taskService, promiseService, disputeService, emailWriter, placeholders,
                    roleResolver, invoiceRepository, userRepository, approvalContext);
        }

        public static void reset() {
            fail = null;
            interleave = null;
        }

        @Override
        public Outcome perform(Act act) {
            Runnable between = interleave;
            if (between != null) {
                interleave = null;
                between.run();
            }
            RuntimeException boom = fail;
            if (boom != null) throw boom;
            return super.perform(act);
        }
    }
}

package com.geneinvoice.automation;

import com.geneinvoice.GeneInvoiceApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What {@code app.automation.queue} is allowed to say (R5, D-75).
 *
 * <p>Mostly without a Spring context, because the check runs while the container is being built
 * and what is worth pinning is the sentence it throws: that is what an operator reads in the
 * start-up log, and it is the only thing standing between them and "no qualifying bean of type
 * AutomationQueue". The last test here does start the application, to be sure that sentence is
 * what a real start-up actually reaches.
 */
class AutomationQueueConfigTest {

    /**
     * The setting used to offer {@code rabbit} in the comment beside it. Nothing implements it, and
     * the in-process bean is conditional on this very value, so choosing it left no AutomationQueue
     * bean at all and the service died on a message about Spring wiring. It still refuses to start
     * — automation is not optional, so a transport nobody can supply must stop the deployment — but
     * it now says which setting, what was asked for, and what there is instead.
     */
    @Test
    void aTransportThisBuildDoesNotHaveIsRefusedByNameAndNotAsABeanError() {
        assertThatThrownBy(() -> AutomationQueueCheck.requireTransport("rabbit"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.automation.queue is 'rabbit'")
                .hasMessageContaining("AUTOMATION_QUEUE")
                .hasMessageContaining("in-process")
                .hasMessageNotContaining("qualifying bean");
    }

    /** An emptied-out environment variable is a value too, and not one the bean's condition takes. */
    @Test
    void anEmptyValueIsRefusedRatherThanTreatedAsTheDefault() {
        assertThatThrownBy(() -> AutomationQueueCheck.requireTransport(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.automation.queue is ''");
    }

    /**
     * The transport this build has, read exactly as {@code @ConditionalOnProperty} reads it: case
     * does not matter, and a missing property is the in-process default the condition matches.
     * A value this accepted and the condition did not would put the bean error straight back.
     */
    @Test
    void theTransportThisBuildHasIsAcceptedHoweverItIsSpelt() {
        assertThatCode(() -> {
            AutomationQueueCheck.requireTransport("in-process");
            AutomationQueueCheck.requireTransport("IN-PROCESS");
            AutomationQueueCheck.requireTransport(null);
        }).doesNotThrowAnyException();
        assertThat(AutomationQueueCheck.TRANSPORTS).containsExactly("in-process");
    }

    /** Whitespace is not trimmed, because the condition on the bean does not trim it either. */
    @Test
    void aValueTheConditionWouldNotMatchIsNotQuietlyTidiedUp() {
        assertThatThrownBy(() -> AutomationQueueCheck.requireTransport(" in-process"))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * And the whole of it, through a real start-up: the deployment stops, and what it says names
     * the setting rather than the bean nobody could supply. This is the sentence the fix is for —
     * before it, the same start-up ended on "no qualifying bean of type AutomationQueue", because
     * the in-process transport is conditional on the very value that had just been changed.
     *
     * <p>Its own in-memory database and a port the operating system picks: this boots a second
     * context beside the one every other test shares, and one that took the shared schema or the
     * shared port down with it as it failed would take those tests with it.
     */
    @Test
    void theServiceRefusesToStartOnATransportItDoesNotHaveAndSaysWhich() {
        assertThatThrownBy(() -> startWith("rabbit"))
                // Thrown as it is, not wrapped: a bean factory post-processor's failure is the
                // start-up's failure, which is why this reads as a sentence and not as a chain.
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.automation.queue is 'rabbit'")
                .hasMessageContaining("AUTOMATION_QUEUE")
                .hasMessageNotContaining("qualifying bean");
    }

    /**
     * Started the way a deployment starts it, with the setting on the command line. Command-line
     * arguments and not {@code properties(..)}: those are default properties, which application.yml
     * then overrides, so the run would have been given the very value it was meant to replace.
     */
    private void startWith(String queue) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                GeneInvoiceApplication.class)
                .web(WebApplicationType.SERVLET)
                .profiles("test")
                .run("--app.automation.queue=" + queue,
                        // A port the operating system picks, and a database of its own: this
                        // context is started beside the one every other test shares, and must not
                        // take either out from under it when it fails.
                        "--server.port=0",
                        "--spring.datasource.url=jdbc:h2:mem:automation-queue-check;DB_CLOSE_DELAY=-1")) {
            assertThat(context.isRunning()).isTrue();
        }
    }
}

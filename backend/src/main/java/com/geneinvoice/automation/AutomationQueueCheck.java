package com.geneinvoice.automation;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The one thing that has to be true about {@code app.automation.queue} before this service starts:
 * that it names a transport this build actually has (R5).
 *
 * <p>{@link InProcessAutomationQueue} is that transport, and today it is the only one. It carries
 * {@code @ConditionalOnProperty(havingValue = "in-process")}, so any other value leaves no
 * {@link AutomationQueue} bean at all — and both {@link AutomationEvents} and
 * {@link AutomationScheduler} take one by constructor injection, so the whole application failed to
 * start on "no qualifying bean of type AutomationQueue". That message is true and useless: it
 * describes the wiring rather than the decision, and the operator who met it had done nothing worse
 * than believe the comment beside the setting, which offered {@code rabbit} as though it existed
 * (D-75, regression 2026-09-21). The comment now says what there is; this says so again at start-up,
 * where a typo or a stale runbook actually arrives.
 *
 * <p>Refusing to start is the right answer rather than quietly falling back to in-process.
 * Automation is not optional to this app — rules raise the tasks somebody is counting on and send
 * the emails a customer is waiting for — so a deployment asking for a transport nobody can supply
 * has to stop while a person is watching. Starting instead, under a transport the operator did not
 * ask for, would be a second quiet answer to a question they meant to decide.
 *
 * <p>It is a {@link BeanFactoryPostProcessor} and not an ordinary bean because ordinary beans are
 * built in whatever order their dependencies imply, and {@code AutomationEvents} asking for the
 * queue it cannot have got there first — the bean error is exactly what an ordinary bean here
 * still produced. A post-processor runs before any of them, so the message an operator reads is
 * this one. It takes the environment through {@link EnvironmentAware} rather than its constructor
 * for the same reason: the processor that does constructor injection is not registered yet.
 *
 * <p>The value is read exactly as the condition on the bean reads it — case-insensitively, with no
 * trimming — because a value this accepted and that one did not would put the bean error straight
 * back.
 */
@Component
public class AutomationQueueCheck implements BeanFactoryPostProcessor, EnvironmentAware {

    /** Every transport this build ships. A broker-backed one is a follow-up; see the class note. */
    public static final List<String> TRANSPORTS = List.of("in-process");

    static final String PROPERTY = "app.automation.queue";

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        requireTransport(environment.getProperty(PROPERTY));
    }

    /**
     * @param queue the configured value, or null where the property is absent altogether — which is
     *              the in-process default, since the bean's condition matches a missing property
     * @throws IllegalStateException naming the value, the property and what there is instead
     */
    static void requireTransport(String queue) {
        if (queue == null) return;
        if (TRANSPORTS.stream().anyMatch(t -> t.equalsIgnoreCase(queue))) return;
        throw new IllegalStateException(
                PROPERTY + " is '" + queue + "', which is not a transport this build has. "
                        + "Set AUTOMATION_QUEUE to one of " + TRANSPORTS + ". A broker-backed "
                        + "transport is a documented follow-up and is not wired here; nothing is "
                        + "lost by running in-process, because the outbox row makes the work "
                        + "durable and the sweep re-drives it (R3).");
    }
}

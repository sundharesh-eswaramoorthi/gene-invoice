package com.geneinvoice.mail.message;

import com.geneinvoice.mail.config.RabbitTopology;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Hands each queue message to the {@link SendWorker}. The worker settles every failure it can on the
 * copy itself; what escapes it (the database gone, say) rejects the message into
 * {@code mail.send.dead}, and the copy's row stays for the sweeper.
 */
@Component
@ConditionalOnProperty(prefix = "mail", name = "queue", havingValue = "rabbit", matchIfMissing = true)
@Slf4j
public class SendListener {

    private final SendWorker worker;

    public SendListener(SendWorker worker) {
        this.worker = worker;
    }

    @RabbitListener(queues = RabbitTopology.SEND_QUEUE, containerFactory = RabbitTopology.LISTENER_FACTORY)
    public void onMessage(SendQueue.Item item) {
        try {
            worker.process(item.id());
        } catch (RuntimeException e) {
            log.error("Copy {} could not be processed; its message goes to {}", item.id(), RabbitTopology.DEAD_QUEUE, e);
            throw new AmqpRejectAndDontRequeueException("Copy " + item.id() + " could not be processed", e);
        }
    }
}

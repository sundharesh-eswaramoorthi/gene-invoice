package com.geneinvoice.mail.message;

import com.geneinvoice.mail.config.MailProperties;
import com.geneinvoice.mail.config.RabbitTopology;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The send queue on RabbitMQ (§4.5): persistent JSON messages on the durable {@code mail.send}, and a
 * retry through the delay queue that fits its wait.
 */
@Component
@ConditionalOnProperty(prefix = "mail", name = "queue", havingValue = "rabbit", matchIfMissing = true)
public class RabbitSendQueue implements SendQueue {

    private final RabbitTemplate rabbit;
    private final MailProperties properties;

    public RabbitSendQueue(RabbitTemplate rabbit, MailProperties properties) {
        this.rabbit = rabbit;
        this.properties = properties;
    }

    @Override
    public void enqueue(long id) {
        rabbit.convertAndSend(RabbitTopology.SEND_EXCHANGE, RabbitTopology.SEND_KEY, new Item(id));
    }

    @Override
    public void enqueueRetry(long id, Duration delay) {
        String key = delay.compareTo(properties.getSend().retryDelay(1)) <= 0
                ? RabbitTopology.RETRY_SHORT_KEY : RabbitTopology.RETRY_LONG_KEY;
        rabbit.convertAndSend(RabbitTopology.RETRY_EXCHANGE, key, new Item(id));
    }
}

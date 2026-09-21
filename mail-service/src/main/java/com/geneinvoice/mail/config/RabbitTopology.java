package com.geneinvoice.mail.config;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@ConditionalOnProperty(prefix = "mail", name = "queue", havingValue = "rabbit", matchIfMissing = true)
public class RabbitTopology {

    public static final String SEND_EXCHANGE = "mail.send";
    public static final String SEND_QUEUE = "mail.send";
    public static final String SEND_KEY = "send";
    public static final String RETRY_EXCHANGE = "mail.retry";
    public static final String RETRY_SHORT_QUEUE = "mail.send.retry.1m";
    public static final String RETRY_LONG_QUEUE = "mail.send.retry.5m";
    public static final String RETRY_SHORT_KEY = "1m";
    public static final String RETRY_LONG_KEY = "5m";
    public static final String DEAD_EXCHANGE = "mail.dead";
    public static final String DEAD_QUEUE = "mail.send.dead";
    public static final String LISTENER_FACTORY = "sendListenerFactory";

    @Bean
    public Declarables mailTopology(MailProperties properties) {
        DirectExchange send = new DirectExchange(SEND_EXCHANGE, true, false);
        DirectExchange retry = new DirectExchange(RETRY_EXCHANGE, true, false);
        FanoutExchange dead = new FanoutExchange(DEAD_EXCHANGE, true, false);

        Queue sendQueue = QueueBuilder.durable(SEND_QUEUE).deadLetterExchange(DEAD_EXCHANGE).build();
        Queue retryShort = retryQueue(RETRY_SHORT_QUEUE, properties.getSend().retryDelay(1));
        Queue retryLong = retryQueue(RETRY_LONG_QUEUE, properties.getSend().retryDelay(2));
        Queue deadQueue = QueueBuilder.durable(DEAD_QUEUE).build();

        Binding sendBinding = BindingBuilder.bind(sendQueue).to(send).with(SEND_KEY);
        Binding shortBinding = BindingBuilder.bind(retryShort).to(retry).with(RETRY_SHORT_KEY);
        Binding longBinding = BindingBuilder.bind(retryLong).to(retry).with(RETRY_LONG_KEY);
        Binding deadBinding = BindingBuilder.bind(deadQueue).to(dead);

        return new Declarables(send, retry, dead, sendQueue, retryShort, retryLong, deadQueue,
                sendBinding, shortBinding, longBinding, deadBinding);
    }

    private static Queue retryQueue(String name, Duration delay) {
        return QueueBuilder.durable(name)
                .ttl((int) Math.min(Integer.MAX_VALUE, delay.toMillis()))
                .deadLetterExchange(SEND_EXCHANGE)
                .deadLetterRoutingKey(SEND_KEY)
                .build();
    }

    @Bean
    public MessageConverter mailMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean(LISTENER_FACTORY)
    public SimpleRabbitListenerContainerFactory sendListenerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory,
            MessageConverter mailMessageConverter, MailProperties properties) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setConcurrentConsumers(properties.getSend().getConcurrency());
        factory.setMaxConcurrentConsumers(properties.getSend().getMaxConcurrency());
        factory.setPrefetchCount(2);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setDefaultRequeueRejected(false);
        factory.setMessageConverter(mailMessageConverter);
        return factory;
    }
}

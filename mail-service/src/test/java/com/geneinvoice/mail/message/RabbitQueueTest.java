package com.geneinvoice.mail.message;

import com.geneinvoice.mail.FakeGoogle;
import com.geneinvoice.mail.FakeGoogle.Reply;
import com.geneinvoice.mail.config.RabbitTopology;
import com.geneinvoice.mail.connection.ConnectRequest;
import com.geneinvoice.mail.connection.ConnectionService;
import com.geneinvoice.mail.events.MailEventRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "mail.queue=rabbit",
        "mail.send.retry-delays=PT1S,PT2S",
        "mail.send.concurrency=2",
        "mail.send.max-concurrency=4",
        "spring.datasource.url=jdbc:h2:mem:genemail-rabbit;DB_CLOSE_DELAY=-1",
        "management.health.rabbit.enabled=true"
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class RabbitQueueTest {

    @Container
    static final RabbitMQContainer rabbit = new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management"));

    static final FakeGoogle google = FakeGoogle.start();

    @DynamicPropertySource
    static void pointAtRabbitAndGoogle(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbit::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbit::getAdminPassword);
        registry.add("mail.google.api-base-url", google::baseUrl);
        registry.add("mail.google.token-url", google::tokenUrl);
        registry.add("mail.google.tokeninfo-url", google::tokeninfoUrl);
        registry.add("mail.google.revoke-url", google::revokeUrl);
    }

    @AfterAll
    static void stopGoogle() {
        google.close();
    }

    @Autowired ConnectionService connections;
    @Autowired MessageService messages;
    @Autowired MailMessageRepository messageRepository;
    @Autowired MailEventRepository eventRepository;
    @Autowired SendQueue queue;
    @Autowired AmqpAdmin admin;
    @Autowired RabbitTemplate rabbitTemplate;

    private static void waitFor(String what, BooleanSupplier done) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!done.getAsBoolean()) {
            assertThat(System.nanoTime()).as("waiting for %s", what).isLessThan(deadline);
            Thread.sleep(100);
        }
    }

    private MessageStatus status(String externalId) {
        return messageRepository.findByExternalId(externalId).map(MailMessage::getStatus).orElse(null);
    }

    @Test
    void copiesGoThroughTheQueueAndAPassingFailureComesBackThroughTheDelayQueue() throws Exception {
        assertThat(queue).isInstanceOf(RabbitSendQueue.class);
        google.account("jane@gmail.com", "1//refresh-7");
        connections.connect("7", new ConnectRequest("Jane Doe", "client-7", "secret-7", "1//refresh-7"));
        AtomicInteger calls = new AtomicInteger();
        google.on("POST", FakeGoogle.api("/messages/send"), exchange -> {
            int n = calls.incrementAndGet();
            return n == 1
                    ? new Reply(503, FakeGoogle.gmailError(503, "Backend Error"))
                    : new Reply(200, "{\"id\":\"gm-" + n + "\",\"threadId\":\"th-" + n + "\"}");
        });

        List<CopyState> states = messages.submit(new SubmitRequest(new SubmitRequest.Sender("7", "Jane Doe"),
                "Invoice INV-0042", "Please pay.", "91", false, List.of(
                new SubmitRequest.Copy("gi-91-1", new SubmitRequest.Recipient("Bob", "bob@acme.com")),
                new SubmitRequest.Copy("gi-91-2", new SubmitRequest.Recipient("Ravi", "ravi@acme.com")))));
        assertThat(states).extracting(CopyState::status).containsOnly(MessageStatus.QUEUED);

        waitFor("both copies sent", () -> status("gi-91-1") == MessageStatus.SENT && status("gi-91-2") == MessageStatus.SENT);

        List<MailMessage> sent = messageRepository.findAll();
        assertThat(sent).extracting(MailMessage::getAttempts).containsExactlyInAnyOrder(1, 2);
        MailMessage retried = sent.stream().filter(m -> m.getAttempts() == 2).findFirst().orElseThrow();
        assertThat(retried.getError()).isNull();
        assertThat(Duration.between(retried.getCreatedAt(), retried.getSentAt())).isGreaterThanOrEqualTo(Duration.ofSeconds(1));
        assertThat(eventRepository.findByTypeOrderByIdAsc("message.status")).filteredOn(e -> retried.getExternalId().equals(e.getExternalId()))
                .extracting(e -> e.getPayload().replaceAll(".*\"status\":\"([A-Z_]+)\".*", "$1"))
                .containsExactly("QUEUED", "SENDING", "QUEUED", "SENDING", "SENT");
        assertThat(google.requests("POST", FakeGoogle.api("/messages/send"))).hasSize(3);
    }

    @Test
    void theAppDeclaresItsQueuesAndAMessageNobodyCanReadIsDeadLettered() {
        for (String name : List.of(RabbitTopology.SEND_QUEUE, RabbitTopology.RETRY_SHORT_QUEUE,
                RabbitTopology.RETRY_LONG_QUEUE, RabbitTopology.DEAD_QUEUE)) {
            assertThat(admin.getQueueInfo(name)).as(name).isNotNull();
        }

        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbitTemplate.send(RabbitTopology.SEND_EXCHANGE, RabbitTopology.SEND_KEY,
                new Message("not json".getBytes(StandardCharsets.UTF_8), properties));

        Message dead = rabbitTemplate.receive(RabbitTopology.DEAD_QUEUE, 20_000);
        assertThat(dead).isNotNull();
        assertThat(new String(dead.getBody(), StandardCharsets.UTF_8)).isEqualTo("not json");
    }
}

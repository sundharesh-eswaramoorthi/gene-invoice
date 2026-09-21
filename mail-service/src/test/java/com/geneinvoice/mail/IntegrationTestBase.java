package com.geneinvoice.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geneinvoice.mail.config.MailProperties;
import com.geneinvoice.mail.connection.ConnectRequest;
import com.geneinvoice.mail.connection.ConnectionService;
import com.geneinvoice.mail.connection.MailConnection;
import com.geneinvoice.mail.connection.MailConnectionRepository;
import com.geneinvoice.mail.events.MailEvent;
import com.geneinvoice.mail.events.MailEventRepository;
import com.geneinvoice.mail.events.WebhookDispatcher;
import com.geneinvoice.mail.message.CopyState;
import com.geneinvoice.mail.message.MailMessage;
import com.geneinvoice.mail.message.MailMessageRepository;
import com.geneinvoice.mail.message.MessageService;
import com.geneinvoice.mail.message.SendWorker;
import com.geneinvoice.mail.message.SubmitRequest;
import com.geneinvoice.mail.tracking.MailInboundRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(IntegrationTestBase.TestBeans.class)
public abstract class IntegrationTestBase {

    protected static final String API_KEY = "test-api-key-0123456789";
    protected static final Instant T0 = Instant.parse("2026-09-20T10:00:00Z");
    protected static final FakeGoogle google = FakeGoogle.start();

    @DynamicPropertySource
    static void pointAtFakeGoogle(DynamicPropertyRegistry registry) {
        registry.add("mail.google.api-base-url", google::baseUrl);
        registry.add("mail.google.token-url", google::tokenUrl);
        registry.add("mail.google.tokeninfo-url", google::tokeninfoUrl);
        registry.add("mail.google.revoke-url", google::revokeUrl);
    }

    @TestConfiguration
    static class TestBeans {

        @Bean
        RecordingSendQueue recordingSendQueue() {
            return new RecordingSendQueue();
        }

        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock();
        }
    }

    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected MailProperties properties;
    @Autowired protected MovableClock clock;
    @Autowired protected RecordingSendQueue queue;
    @Autowired protected MailConnectionRepository connectionRepository;
    @Autowired protected MailMessageRepository messageRepository;
    @Autowired protected MailInboundRepository inboundRepository;
    @Autowired protected MailEventRepository eventRepository;
    @Autowired protected ConnectionService connectionService;
    @Autowired protected MessageService messageService;
    @Autowired protected SendWorker worker;
    @Autowired protected WebhookDispatcher webhookDispatcher;

    @BeforeEach
    void resetEverything() {
        eventRepository.deleteAll();
        inboundRepository.deleteAll();
        messageRepository.deleteAll();
        connectionRepository.deleteAll();
        google.reset();
        queue.reset();
        clock.set(T0);
        properties.getWebhook().setUrl(null);
        properties.getWebhook().setBatchSize(100);
        ReflectionTestUtils.setField(webhookDispatcher, "wait", null);
        ReflectionTestUtils.setField(webhookDispatcher, "nextTryAt", Instant.MIN);
    }

    protected ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("X-Api-Key", API_KEY));
    }

    protected ResultActions call(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return mockMvc.perform(request.header("X-Api-Key", API_KEY)
                .contentType("application/json").content(objectMapper.writeValueAsString(body)));
    }

    protected JsonNode read(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    protected MailConnection connect(String ownerRef, String name, String gmail) {
        google.account(gmail, refreshToken(ownerRef));
        connectionService.connect(ownerRef, new ConnectRequest(name, "client-" + ownerRef + ".apps.googleusercontent.com",
                "secret-" + ownerRef, refreshToken(ownerRef)));
        return connection(ownerRef);
    }

    protected static String refreshToken(String ownerRef) {
        return "1//refresh-" + ownerRef;
    }

    protected MailConnection connection(String ownerRef) {
        return connectionRepository.findByOwnerRef(ownerRef).orElseThrow();
    }

    protected static SubmitRequest submission(String senderRef, String senderName, String groupRef, boolean retry,
                                              SubmitRequest.Copy... copies) {
        return new SubmitRequest(new SubmitRequest.Sender(senderRef, senderName), "Invoice INV-0042",
                "Please find the invoice below.\nThanks", groupRef, retry, Arrays.asList(copies));
    }

    protected static SubmitRequest.Copy copy(String externalId, String name, String address) {
        return new SubmitRequest.Copy(externalId, new SubmitRequest.Recipient(name, address));
    }

    protected List<CopyState> submit(SubmitRequest request) {
        return messageService.submit(request);
    }

    protected void work() {
        queue.take().forEach(worker::process);
    }

    protected MailMessage message(String externalId) {
        return messageRepository.findByExternalId(externalId).orElseThrow();
    }

    protected List<MailEvent> events(String type) {
        return eventRepository.findByTypeOrderByIdAsc(type);
    }

    protected JsonNode payload(MailEvent event) {
        try {
            return objectMapper.readTree(event.getPayload());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    protected List<String> statusTrail(String externalId) {
        return events("message.status").stream()
                .filter(e -> externalId.equals(e.getExternalId()))
                .map(this::payload)
                .map(p -> p.get("status").asText() + "@" + p.get("seq").asLong())
                .toList();
    }

    protected static Map<String, Object> map(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]);
        return map;
    }
}

package com.geneinvoice.mail.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Writes an event into the outbox. Only inside the transaction of the change it reports, so the two
 * are saved together or not at all; the {@link WebhookDispatcher} delivers it afterwards.
 */
@Component
public class EventRecorder {

    public static final String MESSAGE_STATUS = "message.status";
    public static final String MESSAGE_RECEIVED = "message.received";
    public static final String CONNECTION_STATUS = "connection.status";

    private final MailEventRepository events;
    private final ObjectMapper json;
    private final Clock clock;

    public EventRecorder(MailEventRepository events, ObjectMapper json, Clock clock) {
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String type, String ownerRef, String externalId, Object data) {
        String payload;
        try {
            payload = json.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Event " + type + " could not be written as JSON", e);
        }
        events.save(MailEvent.builder()
                .type(type)
                .ownerRef(ownerRef)
                .externalId(externalId)
                .payload(payload)
                .createdAt(clock.instant())
                .build());
    }
}

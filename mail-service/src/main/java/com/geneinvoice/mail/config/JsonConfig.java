package com.geneinvoice.mail.config;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * How far the JSON reader is allowed to go. Jackson refuses a single string longer than 20 million
 * characters by default, and an attached file arrives as exactly that: one base64 string, a third
 * longer than the file itself. A submit at the ceiling ({@code mail.send.max-attachment-bytes},
 * 17 MiB) carries about 23.8 million characters, so the default would turn a perfectly good email
 * into "The request body is not valid JSON" — a refusal nobody could act on.
 *
 * <p>The limit is raised to {@link #MAX_STRING_LENGTH}, above the largest submission
 * {@code mail.send.max-attachment-bytes} can be set to at all
 * ({@link MailProperties#MAX_ATTACHMENT_BYTES_LIMIT}, about 25 million characters once encoded).
 * It is raised, not removed: the guard against a body meant to exhaust memory stays, at a number
 * this service can actually reach. {@code MessageService} refuses anything over the configured
 * ceiling with a message that says the number, which is where an over-large email should be told
 * off — not in the parser.
 */
@Configuration
public class JsonConfig {

    static final int MAX_STRING_LENGTH = 40_000_000;

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer largeAttachmentsFitInOneString() {
        return builder -> builder.factory(JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxStringLength(MAX_STRING_LENGTH)
                        .build())
                .build());
    }
}
